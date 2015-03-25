package com.orchestral.analytics.sandbox

import org.elasticsearch.node.NodeBuilder
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.common.xcontent.XContentFactory
import org.elasticsearch.action.percolate.PercolateSourceBuilder

object Sandbox {

  def main(args: Array[String]): Unit = {
    val node = NodeBuilder.nodeBuilder().clusterName("jrf-escluster").client(true).node()
    val client = node.client()

    val doc: String = s"""{"author":"Stoker, Bram","isbn-10":"1503261387","isbn-13":"978-1503261389","publisher":"CreateSpace Independent Publishing Platform","datePublished":"2014-11-28","language":"English","title":"Dracula","price":9.99}"""
    val xc = XContentFactory.jsonBuilder()
      .field(doc)

    val response = client.preparePercolate()
      .setIndices("ohamazon")
      .setDocumentType("book")
      .setPercolateDoc(PercolateSourceBuilder.docBuilder().setDoc(doc))
      .execute()
      .actionGet()
      
    val notifs = response.getMatches()
      .map(m => client.prepareGet(m.getIndex().toString(), ".percolator", m.getId().toString())
        .setFetchSource("userId", "query")
        .execute().actionGet())
      .filter(r => r.getSource() != null && r.getSource().get("userId") != null)
      .map(r => r.getSource().get("userId").toString())
      .toBuffer[String]
    
   notifs.foreach(println)
    
  }

}