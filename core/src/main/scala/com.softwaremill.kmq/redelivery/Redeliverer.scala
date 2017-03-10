package com.softwaremill.kmq.redelivery

import java.time.Duration
import java.util.Collections
import java.util.concurrent.{Future, TimeUnit}

import com.softwaremill.kmq.{EndMarker, KafkaClients, KmqConfig, MarkerKey}
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

class Redeliverer(partition: Partition, producer: KafkaProducer[Array[Byte], Array[Byte]],
                  config: KmqConfig, clients: KafkaClients) extends StrictLogging {

  private val PollTimeout = Duration.ofSeconds(100).toMillis
  private val SendTimeoutSeconds = 60L

  private val tp = new TopicPartition(config.getMsgTopic, partition)

  private val consumer = {
    val c = clients.createConsumer(null, classOf[ByteArrayDeserializer], classOf[ByteArrayDeserializer])
    c.assign(Collections.singleton(tp))
    c
  }

  def redeliver(toRedeliver: List[MarkerKey]) {
    toRedeliver
      .map(m => RedeliveredMarker(m, redeliver(m)))
      .foreach(rm => {
        rm.sendResult.get(SendTimeoutSeconds, TimeUnit.SECONDS)

        // ignoring the result, worst case if this fails the message will be re-processed after restart
        writeEndMarker(rm.marker)
      })
  }

  private def redeliver(marker: MarkerKey): Future[RecordMetadata] = {
    if (marker.getPartition != partition) {
      throw new IllegalStateException(
        s"Got marker key for partition ${marker.getPartition}, while the assigned partition is $partition!")
    }

    consumer.seek(tp, marker.getMessageOffset)
    val pollResults = consumer.poll(PollTimeout).records(tp)
    if (pollResults.isEmpty) {
      throw new IllegalStateException(s"Cannot redeliver $marker from topic ${config.getMsgTopic}, due to data fetch timeout")
    } else {
      val toSend = pollResults.get(0)
      logger.info(s"Redelivering message from ${config.getMsgTopic}, partition ${marker.getPartition}, offset ${marker.getMessageOffset}")
      producer.send(new ProducerRecord(toSend.topic, toSend.partition, toSend.key, toSend.value))
    }
  }

  private def writeEndMarker(marker: MarkerKey): Future[RecordMetadata] = {
    producer.send(new ProducerRecord(config.getMarkerTopic, partition,
      marker.serialize, EndMarker.INSTANCE.serialize()))
  }

  private case class RedeliveredMarker(marker: MarkerKey, sendResult: Future[RecordMetadata])

  def close(): Unit = consumer.close()
}