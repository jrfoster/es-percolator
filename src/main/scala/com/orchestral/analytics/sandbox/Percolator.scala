package com.orchestral.analytics.sandbox

import scala.collection.mutable.Buffer

import org.apache.spark.{ AccumulatorParam, Logging, SparkConf }
import org.apache.spark.streaming.{ Seconds, StreamingContext }
import org.apache.spark.streaming.kafka.KafkaUtils

import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.percolate.PercolateSourceBuilder
import org.elasticsearch.action.percolate.PercolateResponse.Match
import org.elasticsearch.node.{ Node, NodeBuilder }
import org.elasticsearch.spark.sparkRDDFunctions

import kafka.serializer.StringDecoder

object Percolator extends Serializable with Logging {

  val baseParams: Map[String, String] = Map(
    "es.nodes" -> "localhost:9200",
    "es.input.json" -> "true")

  val bookParams: Map[String, String] = baseParams + ("es.resource" -> "ohamazon/book")
  val notifParams: Map[String, String] = baseParams + ("es.resource" -> "ohamazon/notifications")

  def main(args: Array[String]): Unit = {

    val conf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("KafkaPercolator")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.streaming.unpersist", "true")

    val ssc = new StreamingContext(conf, Seconds(30))

    val kafkaParams = Map[String, String]("metadata.broker.list" -> "jrf-sandbox:9092")
    val topics = Set("books")
    val stream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topics)

    stream.foreachRDD(rdd => {
      val messages = rdd.map(_._2)

      // Save them to ES for generalized search
      messages.saveToEs(bookParams)

      // Percolate each document and use the accumulator to keep matches.  The
      // foreach is done on the workers, so we use an accumulator to keep all
      // the notifications and manage that in the driver
      val notifs = ssc.sparkContext.accumulator(Buffer[String]())(BufferAccumulatorParam)
      messages.foreach(document => {
        notifs += doPercolation(document)
      })

      // Save the matches to ElasticSearch
      val notifsRdd = ssc.sparkContext.makeRDD[String](notifs.value)
      notifsRdd.saveToEs(notifParams)
    })

    ssc.start()
    ssc.awaitTermination
  }

  private def doPercolation(doc: String): Buffer[String] = {
    var node: Node = null

    try {
      node = NodeBuilder.nodeBuilder().clusterName("jrf-escluster").client(true).node()

      val response = node.client.preparePercolate()
        .setIndices("ohamazon")
        .setDocumentType("book")
        .setPercolateDoc(PercolateSourceBuilder.docBuilder().setDoc(doc))
        .execute().actionGet()

      val notifs = Buffer[String]()
      response.getMatches().foreach(m => {
        val index = m.getIndex().toString()
        val queryId = m.getId().toString()
        val matchedQuery = node.client.prepareGet(index, ".percolator", queryId)
          .setFetchSource("userId", "query")
          .execute().actionGet()
        val userId = matchedQuery.getSource().get("userId").toString()
        notifs += s"""{"userId":"${userId}","queryMoniker":"${queryId}","data":""}"""
      })

      notifs

    } catch {
      case ex: Exception =>
        logWarning("Error occurred during percolation", ex)
        Buffer[String]()
    } finally {
      if (node != null) node.close()
    }
  }
}

object BufferAccumulatorParam extends AccumulatorParam[Buffer[String]] {

  def zero(initialValue: Buffer[String]): Buffer[String] = {
    Buffer[String]()
  }

  def addInPlace(v1: Buffer[String], v2: Buffer[String]): Buffer[String] = {
    v1 ++ v2
  }
}