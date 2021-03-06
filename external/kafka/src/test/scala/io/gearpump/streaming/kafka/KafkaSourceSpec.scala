/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.streaming.kafka

import scala.util.{Failure, Success}

import com.twitter.bijection.Injection
import kafka.common.TopicAndPartition
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalacheck.Gen
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

import io.gearpump.Message
import io.gearpump.streaming.kafka.lib.consumer.{FetchThread, KafkaMessage}
import io.gearpump.streaming.kafka.lib.{KafkaOffsetManager, KafkaSourceConfig}
import io.gearpump.streaming.transaction.api.OffsetStorage.StorageEmpty
import io.gearpump.streaming.transaction.api.{MessageDecoder, OffsetStorageFactory, TimeStampFilter}

class KafkaSourceSpec extends PropSpec with PropertyChecks with Matchers with MockitoSugar {

  val startTimeGen = Gen.choose[Long](0L, 1000L)
  val offsetGen = Gen.choose[Long](0L, 1000L)

  property("KafkaSource open sets consumer to earliest offset") {
    val topicAndPartition = mock[TopicAndPartition]
    val fetchThread = mock[FetchThread]
    val offsetManager = mock[KafkaOffsetManager]
    val messageDecoder = mock[MessageDecoder]
    val timestampFilter = mock[TimeStampFilter]
    val offsetStorageFactory = mock[OffsetStorageFactory]
    val kafkaConfig = mock[KafkaSourceConfig]
    val kafkaSource = new KafkaSource(kafkaConfig, offsetStorageFactory, messageDecoder,
      timestampFilter, Some(fetchThread), Map(topicAndPartition -> offsetManager))

    kafkaSource.setStartTime(None)

    verify(fetchThread).start()
    verify(fetchThread, never()).setStartOffset(anyObject[TopicAndPartition](), anyLong())
  }

  property("KafkaSource open should not set consumer start offset if offset storage is empty") {
    forAll(startTimeGen) { (startTime: Long) =>
      val offsetManager = mock[KafkaOffsetManager]
      val topicAndPartition = mock[TopicAndPartition]
      val fetchThread = mock[FetchThread]
      val messageDecoder = mock[MessageDecoder]
      val timestampFilter = mock[TimeStampFilter]
      val offsetStorageFactory = mock[OffsetStorageFactory]
      val kafkaConfig = mock[KafkaSourceConfig]
      val source = new KafkaSource(kafkaConfig, offsetStorageFactory, messageDecoder,
        timestampFilter, Some(fetchThread), Map(topicAndPartition -> offsetManager))

      when(offsetManager.resolveOffset(startTime)).thenReturn(Failure(StorageEmpty))

      source.setStartTime(Some(startTime))
      verify(fetchThread, never()).setStartOffset(anyObject[TopicAndPartition](), anyLong())
      verify(fetchThread).start()

      when(offsetManager.resolveOffset(startTime)).thenReturn(Failure(new RuntimeException))
      intercept[RuntimeException] {
        source.setStartTime(Some(startTime))
      }
      source.close()
    }
  }

  property("KafkaSource open should set consumer start offset if offset storage is not empty") {
    forAll(startTimeGen, offsetGen) {
      (startTime: Long, offset: Long) =>
        val offsetManager = mock[KafkaOffsetManager]
        val topicAndPartition = mock[TopicAndPartition]
        val fetchThread = mock[FetchThread]
        val messageDecoder = mock[MessageDecoder]
        val timestampFilter = mock[TimeStampFilter]
        val offsetStorageFactory = mock[OffsetStorageFactory]
        val kafkaConfig = mock[KafkaSourceConfig]
        val source = new KafkaSource(kafkaConfig, offsetStorageFactory, messageDecoder,
          timestampFilter, Some(fetchThread), Map(topicAndPartition -> offsetManager))

        when(offsetManager.resolveOffset(startTime)).thenReturn(Success(offset))

        source.setStartTime(Some(startTime))
        verify(fetchThread).setStartOffset(topicAndPartition, offset)
        verify(fetchThread).start()

        when(offsetManager.resolveOffset(startTime)).thenReturn(Failure(new RuntimeException))
        intercept[RuntimeException] {
          source.setStartTime(Some(startTime))
        }
        source.close()
    }
  }

  property("KafkaSource read should return number of messages in best effort") {
    val numberGen = Gen.choose[Int](0, 1000)

    val kafkaMsgGen = for {
      topic <- Gen.alphaStr
      partition <- Gen.choose[Int](0, 1000)
      offset <- Gen.choose[Long](0L, 1000L)
      key = None
      msg <- Gen.alphaStr.map(Injection[String, Array[Byte]])
    } yield KafkaMessage(TopicAndPartition(topic, partition), offset, key, msg)
    val kafkaMsgListGen = Gen.listOf[KafkaMessage](kafkaMsgGen) suchThat (_.size > 0)
    forAll(numberGen, kafkaMsgListGen) {
      (number: Int, kafkaMsgList: List[KafkaMessage]) =>
        val offsetManager = mock[KafkaOffsetManager]
        val fetchThread = mock[FetchThread]
        val messageDecoder = mock[MessageDecoder]
        val message = mock[Message]

        val timestampFilter = mock[TimeStampFilter]
        val offsetStorageFactory = mock[OffsetStorageFactory]
        val kafkaConfig = mock[KafkaSourceConfig]
        val offsetManagers = kafkaMsgList.map(_.topicAndPartition -> offsetManager).toMap

        val source = new KafkaSource(kafkaConfig, offsetStorageFactory, messageDecoder,
          timestampFilter, Some(fetchThread), offsetManagers)

        if (number == 0) {
          verify(fetchThread, never()).poll
          source.read(number).size shouldBe 0
        } else {
          kafkaMsgList match {
            case Nil =>
              if (number == 1) {
                when(fetchThread.poll).thenReturn(None)
              } else {
                val nones = List.fill(number)(None)
                when(fetchThread.poll).thenReturn(nones.head, nones.tail: _*)
              }
            case list =>
              val queue = list.map(Option(_)) ++ List.fill(number - list.size)(None)
              when(fetchThread.poll).thenReturn(queue.head, queue.tail: _*)
              when(messageDecoder.fromBytes(anyObject[Array[Byte]])).thenReturn(message)
              when(offsetManager.filter(anyObject[(Message, Long)])).thenReturn(Some(message))
              when(timestampFilter.filter(anyObject[Message], anyLong())).thenReturn(Some(message))
          }
          source.read(number).size shouldBe Math.min(number, kafkaMsgList.size)
          verify(fetchThread, times(number)).poll
        }
        source.close()
    }
  }

  property("KafkaSource close should close all offset managers") {
    val offsetManager = mock[KafkaOffsetManager]
    val topicAndPartition = mock[TopicAndPartition]
    val fetchThread = mock[FetchThread]
    val timestampFilter = mock[TimeStampFilter]
    val messageDecoder = mock[MessageDecoder]
    val offsetStorageFactory = mock[OffsetStorageFactory]
    val kafkaConfig = mock[KafkaSourceConfig]
    val source = new KafkaSource(kafkaConfig, offsetStorageFactory, messageDecoder,
      timestampFilter, Some(fetchThread), Map(topicAndPartition -> offsetManager))
    source.close()
    verify(offsetManager).close()
  }
}
