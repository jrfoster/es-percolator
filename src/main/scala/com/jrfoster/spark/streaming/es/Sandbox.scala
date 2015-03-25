package com.jrfoster.spark.streaming.es

import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.action.percolate.PercolateSourceBuilder
import scala.collection.mutable.Buffer
import scala.util.Try

case class Person (
  var value: String  = null
)

object Sandbox {

  def main(args: Array[String]): Unit = {
    val node = NodeBuilder.nodeBuilder().clusterName("jrf-escluster").client(true).node()

    val doc: String = s"""{"author":"Stoker, Bram","isbn-10":"1503261387","isbn-13":"978-1503261389","publisher":"CreateSpace Independent Publishing Platform","datePublished":"2014-11-28","language":"English","title":"Dracula","price":9.99}"""

    val response = node.client.preparePercolate()
      .setIndices("ohamazon")
      .setDocumentType("book")
      .setPercolateDoc(PercolateSourceBuilder.docBuilder().setDoc(doc))
      .execute()
      .actionGet()

    def matchMapper(m: org.elasticsearch.action.percolate.PercolateResponse.Match): String = {
      val queryId = m.getId().toString()
      val matchedQuery = node.client.prepareGet(m.getIndex().toString(), ".percolator", queryId)
        .setFetchSource("userId", "query")
        .execute().actionGet()
      val userId = Try(matchedQuery.getSource().get("userId").toString()).getOrElse("")
      s"""{"userId":"${userId}","queryMoniker":"${queryId}","data":""}"""
    }

    val notifs = response.getMatches()
      .map(m => matchMapper(m))
      .toBuffer[String]
      .foreach(println)
      
    node.close()
  }

}