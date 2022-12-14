/*
 * Copyright 2021 Israel Herraiz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.herraiz

import com.spotify.scio.bigquery.{CREATE_IF_NEEDED, Table, _}
import com.spotify.scio.pubsub.PubsubIO
import com.spotify.scio.values.{SCollection, WindowOptions}
import com.spotify.scio.{Args, ContextAndArgs, ScioContext, streaming}
import dev.herraiz.data.DataTypes._
import io.circe
import org.apache.beam.sdk.transforms.windowing.{AfterProcessingTime, AfterWatermark}
import org.joda.time.Duration

object TaxiSessionsPipeline {
  // seconds
  val SESSION_GAP = 600
  val EARLY_RESULT = 10
  val LATENESS = 900

  def main(cmdlineArgs: Array[String]): Unit = {
    val (scontext: ScioContext, opts: Args) = ContextAndArgs(cmdlineArgs)
    implicit val sc: ScioContext = scontext

    val pubsubTopic: String = opts("pubsub-topic")
    val goodTable = opts("output-table")
    val badTable = opts("errors-table")
    val accumTable = opts("accum-table")

    val messages: SCollection[String] = getMessagesFromPubSub(pubsubTopic)
    val (rides, writableErrors) = parseJSONStrings(messages)

    rides.saveAsBigQueryTable(Table.Spec(goodTable), WRITE_APPEND, CREATE_IF_NEEDED)
    writableErrors.saveAsBigQueryTable(Table.Spec(badTable), WRITE_APPEND, CREATE_IF_NEEDED)

    // Group by session with a max duration of 5 mins between events
    // Window options
    val wopts: WindowOptions = customWindowOptions
    val groupRides: SCollection[TaxiRide] = groupRidesByKey(rides.map(_.toTaxiRide), wopts)
    groupRides.saveAsBigQueryTable(Table.Spec(accumTable), WRITE_APPEND, CREATE_IF_NEEDED)

    sc.run
  }

  def customWindowOptions: WindowOptions =
    WindowOptions(
      trigger = AfterWatermark.pastEndOfWindow()
        .withEarlyFirings(AfterProcessingTime
          .pastFirstElementInPane
          .plusDelayOf(Duration.standardSeconds(EARLY_RESULT)))
        .withLateFirings(AfterProcessingTime
          .pastFirstElementInPane()
          .plusDelayOf(Duration.standardSeconds(LATENESS))),
      accumulationMode = streaming.ACCUMULATING_FIRED_PANES,
      allowedLateness = Duration.standardSeconds(LATENESS)
    )

  def getMessagesFromPubSub(pubsubTopic: String)(implicit sc: ScioContext): SCollection[String] = {
    // ???  -> undefine code
    val msgs: PubsubIO[String] = PubsubIO.string(pubsubTopic, timestampAttribute = "ts")
    val param: PubsubIO.ReadParam = PubsubIO.ReadParam(PubsubIO.Topic)

    /*_*/   // to tell the scala, it is ok
    val output: SCollection[String] = sc.read(msgs)(param) /*_*/
    output
  }

  def parseJSONStrings(messages: SCollection[String]):
  (SCollection[PointTaxiRide], SCollection[JsonError]) = {
    val parsed: SCollection[Either[circe.Error, PointTaxiRide]] = messages.map(json2TaxiRide)

    // 0 and 1 is bucket number
    val lefts :: rights :: Nil : Seq[SCollection[Either[circe.Error, PointTaxiRide]]] = parsed.partition(2, { e =>
      e match {
        case Left(_) => 0
        case Right(_1) => 1
      }
    })

    val errors: SCollection[JsonError] = lefts.map(e => circeErrorToCustomError(e.left.get))
    val rides: SCollection[PointTaxiRide] = rights.map(_.right.get)

    (rides, errors)

  }

  def groupRidesByKey(rides: SCollection[TaxiRide], wopts: WindowOptions): SCollection[TaxiRide] = {

    val withKeys: SCollection[(String, TaxiRide)] = rides.keyBy(_.ride_id)

    val windowed: SCollection[(String, TaxiRide)] =
      withKeys.withSessionWindows(Duration.standardSeconds(SESSION_GAP), wopts)

    // _._2 take the second element
    val reduced: SCollection[TaxiRide] = windowed.reduceByKey(_ + _).map(_._2)
    reduced
  }
}
