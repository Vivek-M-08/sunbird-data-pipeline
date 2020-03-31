package org.sunbird.dp.functions

import java.util

import com.google.gson.Gson
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.util.Collector
import org.slf4j.LoggerFactory
import org.sunbird.dp.cache.{ DedupEngine, RedisConnect }
import org.sunbird.dp.task.ExtractionConfig
import org.sunbird.dp.utils.DeDupUtil

class DeduplicationFunction(config: ExtractionConfig, @transient var dedupEngine: DedupEngine = null)(implicit val eventTypeInfo: TypeInformation[util.Map[String, AnyRef]])
  extends ProcessFunction[util.Map[String, AnyRef], util.Map[String, AnyRef]] {

  private[this] val logger = LoggerFactory.getLogger(classOf[DeduplicationFunction])

  override def open(parameters: Configuration): Unit = {
    if (dedupEngine == null) {
      val redisConnect = new RedisConnect(config)
      dedupEngine = new DedupEngine(redisConnect, config.dedupStore, config.cacheExpirySeconds)
    }
  }

  override def close(): Unit = {
    super.close()
    dedupEngine.closeConnectionPool()
  }

  override def processElement(
    batchEvents: util.Map[String, AnyRef],
    context:     ProcessFunction[util.Map[String, AnyRef], util.Map[String, AnyRef]]#Context,
    out:         Collector[util.Map[String, AnyRef]]): Unit = {
    DeDupUtil.deDup[util.Map[String, AnyRef]](getMsgIdentifier(batchEvents), batchEvents, dedupEngine, context, config.uniqueEventOutputTag, config.duplicateEventOutputTag)

    def getMsgIdentifier(batchEvents: util.Map[String, AnyRef]): String = {
      val gson = new Gson();
      val paramsObj = batchEvents.get("params")
      if (null != paramsObj) {
        val messageId = gson.fromJson(gson.toJson(paramsObj), (new util.HashMap[String, AnyRef]()).getClass).get("msgid")
        if (null != messageId)
          messageId.toString
        else null
      } else null
    }
  }
}