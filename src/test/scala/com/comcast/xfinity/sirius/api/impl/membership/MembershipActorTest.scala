/*
 *  Copyright 2012-2014 Comcast Cable Communications Management, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.comcast.xfinity.sirius.api.impl.membership

import com.comcast.xfinity.sirius.{TimedTest, NiceTest}
import org.mockito.Mockito._
import akka.agent.Agent
import scala.concurrent.duration._
import org.mockito.Matchers._
import akka.actor.{ActorSystem, ActorRef}
import akka.testkit.{TestProbe, TestActorRef}
import com.comcast.xfinity.sirius.api.impl.membership.MembershipActor._
import javax.management.{ObjectName, MBeanServer}
import com.comcast.xfinity.sirius.api.SiriusConfiguration
import org.mockito.ArgumentCaptor
import com.comcast.xfinity.sirius.api.impl.membership.MembershipActor.{PingMembership, MembershipInfoMBean}
import com.comcast.xfinity.sirius.util.AkkaExternalAddressResolver

class MembershipActorTest extends NiceTest with TimedTest {

  def makeMembershipActor(clusterConfig: Option[ClusterConfig] = None,
                          membershipAgent: Agent[Map[String, Option[ActorRef]]] = Agent[Map[String, Option[ActorRef]]](Map())(actorSystem.dispatcher),
                          mbeanServer: MBeanServer = mock[MBeanServer]):
                         (TestActorRef[MembershipActor], Agent[Map[String, Option[ActorRef]]]) = {

    val cluster = clusterConfig.getOrElse({
      val mockClusterConfig = mock[ClusterConfig]
      doReturn(List[String]()).when(mockClusterConfig).members
      mockClusterConfig
    })

    val siriusConfig = new SiriusConfiguration
    siriusConfig.setProp(SiriusConfiguration.MBEAN_SERVER, mbeanServer)
    siriusConfig.setProp(SiriusConfiguration.AKKA_EXTERNAL_ADDRESS_RESOLVER,AkkaExternalAddressResolver(actorSystem)(siriusConfig))
    val underTest = TestActorRef[MembershipActor](
      new MembershipActor(membershipAgent, cluster, 120.seconds, 120.seconds, siriusConfig), "membership-actor-test"
    )(actorSystem)

    (underTest, membershipAgent)
  }

  implicit var actorSystem: ActorSystem = _

  before {
    actorSystem = ActorSystem("testsystem")
  }

  after {
    actorSystem.shutdown()
    waitForTrue(actorSystem.isTerminated, 1000, 50)
  }

  describe("a MembershipActor") {
    it("should report on cluster membership if it receives a GetMembershipData message") {
      val senderProbe = TestProbe()(actorSystem)
      val (underTest, _) = makeMembershipActor()

      senderProbe.send(underTest, GetMembershipData)
      senderProbe.expectMsgClass(classOf[Map[String, Option[ActorRef]]])
    }

    it("should add actors to membership when CheckClusterConfig is received") {
      val mockClusterConfig = mock[ClusterConfig]
      val probeOne = TestProbe()
      val probeOnePath = probeOne.ref.path.toString
      val probeTwo = TestProbe()
      val probeTwoPath = probeTwo.ref.path.toString
      doReturn(List(probeOnePath, probeTwoPath)).when(mockClusterConfig).members

      val (underTest, membershipAgent: Agent[Map[String, Option[ActorRef]]]) = makeMembershipActor(clusterConfig = Some(mockClusterConfig))

      underTest ! CheckClusterConfig

      assert(waitForTrue(membershipAgent.get().size == 2, 2000, 100), "Did not reach correct membership size.")
      assert(Some(probeOne.ref) === membershipAgent.get()(probeOnePath))
      assert(Some(probeTwo.ref) === membershipAgent.get()(probeTwoPath))
    }

    it("should not add actors from membership that fail to resolve") {
      val mockClusterConfig = mock[ClusterConfig]

      val probeOne = TestProbe()
      val probeOnePath = probeOne.ref.path.toString
      val probeTwo = TestProbe()
      val probeTwoPath = probeTwo.ref.path.toString
      doReturn(List(probeOnePath, probeTwoPath)).when(mockClusterConfig).members
      actorSystem.stop(probeTwo.ref)

      val (underTest, membershipAgent) = makeMembershipActor(Some(mockClusterConfig))

      underTest ! CheckClusterConfig
      assert(waitForTrue(membershipAgent.get().size == 2, 2000, 100))
      assert(Some(probeOne.ref) === membershipAgent.get()(probeOnePath))
      assert(None === membershipAgent.get()(probeTwoPath))
    }

    it("should remove actors that seem to die") {
      val mockClusterConfig = mock[ClusterConfig]
      val probeOne = TestProbe()
      val probeOnePath = probeOne.ref.path.toString
      val probeTwo = TestProbe()
      val probeTwoPath = probeTwo.ref.path.toString
      doReturn(List(probeOnePath, probeTwoPath)).when(mockClusterConfig).members

      val (underTest, membershipAgent) = makeMembershipActor(Some(mockClusterConfig))


      underTest ! CheckClusterConfig
      assert(waitForTrue(membershipAgent.get().size == 2, 2000, 100))

      actorSystem.stop(probeTwo.ref)
      underTest ! CheckClusterConfig

      assert(waitForTrue(membershipAgent.get().values.flatten.size == 1, 5000, 100))
      assert(Some(probeOne.ref) === membershipAgent.get()(probeOnePath))
      assert(None === membershipAgent.get()(probeTwoPath))
    }

    it("should remove actors from membership that were removed from cluster config") {
      val mockClusterConfig = mock[ClusterConfig]
      val probeOne = TestProbe()
      val probeOnePath = probeOne.ref.path.toString
      val probeTwo = TestProbe()
      val probeTwoPath = probeTwo.ref.path.toString
      doReturn(List(probeOnePath, probeTwoPath)).when(mockClusterConfig).members

      val (underTest, membershipAgent: Agent[Map[String, Option[ActorRef]]]) = makeMembershipActor(clusterConfig = Some(mockClusterConfig))

      underTest ! CheckClusterConfig
      assert(waitForTrue(membershipAgent.get().size == 2, 2000, 100), "Did not reach correct membership size.")

      doReturn(List(probeOnePath)).when(mockClusterConfig).members

      underTest ! CheckClusterConfig

      assert(waitForTrue(membershipAgent.get().size == 1, 2000, 100), "Did not remove missing actorPath from membership.")
      assert(Some(probeOne.ref) === membershipAgent.get()(probeOnePath))
      assert(None === membershipAgent.get().get(probeTwoPath))
    }

    it("should reply properly to a Ping") {
      val senderProbe = TestProbe()(actorSystem)
      val (underTest, _) = makeMembershipActor()

      senderProbe.send(underTest, Ping(30L))
      senderProbe.expectMsg(Pong(30L))
    }

    it("should properly update roundtrip maps on a Pong") {
      val mockMbeanServer = mock[MBeanServer]
      val (underTest, _) = makeMembershipActor(mbeanServer = mockMbeanServer)

      val senderProbe = TestProbe()(actorSystem)
      val senderPath = senderProbe.testActor.path.toString

      val senderProbe2 = TestProbe()(actorSystem)
      val senderPath2 = senderProbe2.testActor.path.toString

      val mbeanCaptor = ArgumentCaptor.forClass(classOf[Any])
      verify(mockMbeanServer).registerMBean(mbeanCaptor.capture(), any[ObjectName])
      val membershipInfo = mbeanCaptor.getValue.asInstanceOf[MembershipInfoMBean]

      senderProbe.send(underTest, Pong(System.currentTimeMillis() - 100L))

      assert(membershipInfo.getTimeSinceLastPingUpdate.keySet.size == 1)
      assert(membershipInfo.getTimeSinceLastPingUpdate.get(senderPath) != None)
      assert(membershipInfo.getMembershipRoundTrip(senderPath) >= 0)

      senderProbe2.send(underTest, Pong(System.currentTimeMillis() - 200L))

      assert(membershipInfo.getTimeSinceLastPingUpdate.keySet.size == 2)
      assert(membershipInfo.getTimeSinceLastPingUpdate.get(senderPath2) != None)
      assert(membershipInfo.getMembershipRoundTrip(senderPath2) >= 0)

    }

    it("should Ping all known members on a PingMembership") {
      val senderProbe1 = TestProbe()(actorSystem)
      val senderProbe2 = TestProbe()(actorSystem)
      val senderProbe3 = TestProbe()(actorSystem)

      val (underTest, membershipAgent) = makeMembershipActor()

      membershipAgent send Map("1" -> Some(senderProbe1.ref),
                               "2" -> Some(senderProbe2.ref),
                               "3" -> Some(senderProbe3.ref))
      waitForTrue(membershipAgent.get().size == 3, 200, 10)

      senderProbe1.send(underTest, PingMembership)

      senderProbe1.expectMsgClass(classOf[Ping])
      senderProbe2.expectMsgClass(classOf[Ping])
      senderProbe3.expectMsgClass(classOf[Ping])
    }
  }
}
