/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, ByteVector32, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements._
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestkitBaseClass, randomKey, _}
import org.scalatest.Outcome
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration._

/**
 * Base class for router testing.
 * It is re-used in payment FSM tests
 * Created by PM on 29/08/2016.
 */

abstract class BaseRouterSpec extends TestkitBaseClass {

  case class FixtureParam(nodeParams: NodeParams, router: ActorRef, watcher: TestProbe)

  val remoteNodeId = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

  val seed = ByteVector32(ByteVector.fill(32)(2))
  val testKeyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f) = (testKeyManager.nodeKey.privateKey, randomKey, randomKey, randomKey, randomKey, randomKey)
  val (a, b, c, d, e, f) = (testKeyManager.nodeId, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey, priv_f.publicKey)

  val (priv_funding_a, priv_funding_b, priv_funding_c, priv_funding_d, priv_funding_e, priv_funding_f) = (randomKey, randomKey, randomKey, randomKey, randomKey, randomKey)
  val (funding_a, funding_b, funding_c, funding_d, funding_e, funding_f) = (priv_funding_a.publicKey, priv_funding_b.publicKey, priv_funding_c.publicKey, priv_funding_d.publicKey, priv_funding_e.publicKey, priv_funding_f.publicKey)

  val node_a = makeNodeAnnouncement(priv_a, "node-A", Color(15, 10, -70), Nil, hex"0200")
  val node_b = makeNodeAnnouncement(priv_b, "node-B", Color(50, 99, -80), Nil, hex"")
  val node_c = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), Nil, hex"0200")
  val node_d = makeNodeAnnouncement(priv_d, "node-D", Color(-120, -20, 60), Nil, hex"00")
  val node_e = makeNodeAnnouncement(priv_e, "node-E", Color(-50, 0, 10), Nil, hex"00")
  val node_f = makeNodeAnnouncement(priv_f, "node-F", Color(30, 10, -50), Nil, hex"00")

  val channelId_ab = ShortChannelId(420000, 1, 0)
  val channelId_bc = ShortChannelId(420000, 2, 0)
  val channelId_cd = ShortChannelId(420000, 3, 0)
  val channelId_ef = ShortChannelId(420000, 4, 0)

  def channelAnnouncement(shortChannelId: ShortChannelId, node1_priv: PrivateKey, node2_priv: PrivateKey, funding1_priv: PrivateKey, funding2_priv: PrivateKey) = {
    val (node1_sig, funding1_sig) = signChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv, node2_priv.publicKey, funding1_priv, funding2_priv.publicKey, ByteVector.empty)
    val (node2_sig, funding2_sig) = signChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, node2_priv, node1_priv.publicKey, funding2_priv, funding1_priv.publicKey, ByteVector.empty)
    makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, node1_sig, node2_sig, funding1_sig, funding2_sig)
  }

  val chan_ab = channelAnnouncement(channelId_ab, priv_a, priv_b, priv_funding_a, priv_funding_b)
  val chan_bc = channelAnnouncement(channelId_bc, priv_b, priv_c, priv_funding_b, priv_funding_c)
  val chan_cd = channelAnnouncement(channelId_cd, priv_c, priv_d, priv_funding_c, priv_funding_d)
  val chan_ef = channelAnnouncement(channelId_ef, priv_e, priv_f, priv_funding_e, priv_funding_f)

  val update_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, b, channelId_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = 500000000 msat)
  val update_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, a, channelId_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = 500000000 msat)
  val update_bc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = 500000000 msat)
  val update_cb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, b, channelId_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = 500000000 msat)
  val update_cd = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = 500000000 msat)
  val update_dc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_d, c, channelId_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = 500000000 msat)
  val update_ef = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_e, f, channelId_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
  val update_fe = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_f, e, channelId_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)

  override def withFixture(test: OneArgTest): Outcome = {
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d and e --(4)--> f (we are a)

    within(30 seconds) {

      // first we make sure that we correctly resolve channelId+direction to nodeId
      assert(Router.getDesc(update_ab, chan_ab) === ChannelDesc(chan_ab.shortChannelId, priv_a.publicKey, priv_b.publicKey))
      assert(Router.getDesc(update_bc, chan_bc) === ChannelDesc(chan_bc.shortChannelId, priv_b.publicKey, priv_c.publicKey))
      assert(Router.getDesc(update_cd, chan_cd) === ChannelDesc(chan_cd.shortChannelId, priv_c.publicKey, priv_d.publicKey))
      assert(Router.getDesc(update_ef, chan_ef) === ChannelDesc(chan_ef.shortChannelId, priv_e.publicKey, priv_f.publicKey))


      // let's set up the router
      val peerConnection = TestProbe()
      val watcher = TestProbe()
      val nodeParams = Alice.nodeParams
      val router = system.actorOf(Router.props(nodeParams, watcher.ref))
      // we announce channels
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ab))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_bc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_cd))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ef))
      // then nodes
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_a))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_b))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_d))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_e))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_f))
      // then channel updates
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ba))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_bc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_cb))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_cd))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_dc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ef))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_fe))
      // watcher receives the get tx requests
      watcher.expectMsg(ValidateRequest(chan_ab))
      watcher.expectMsg(ValidateRequest(chan_bc))
      watcher.expectMsg(ValidateRequest(chan_cd))
      watcher.expectMsg(ValidateRequest(chan_ef))
      // and answers with valid scripts
      watcher.send(router, ValidateResult(chan_ab, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_bc, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_cd, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_c, funding_d)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_ef, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_e, funding_f)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      // watcher receives watch-spent request
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]
      watcher.expectMsgType[WatchSpentBasic]

      val sender = TestProbe()

      awaitCond({
        sender.send(router, 'nodes)
        val nodes = sender.expectMsgType[Iterable[NodeAnnouncement]]
        sender.send(router, 'channels)
        val channels = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        sender.send(router, 'updates)
        val updates = sender.expectMsgType[Iterable[ChannelUpdate]]
        nodes.size === 6 && channels.size === 4 && updates.size === 8
      }, max = 10 seconds, interval = 1 second)

      withFixture(test.toNoArgTest(FixtureParam(nodeParams, router, watcher)))
    }
  }
}

object BaseRouterSpec {
  def channelAnnouncement(channelId: ShortChannelId, node1_priv: PrivateKey, node2_priv: PrivateKey, funding1_priv: PrivateKey, funding2_priv: PrivateKey) = {
    val (node1_sig, funding1_sig) = signChannelAnnouncement(Block.RegtestGenesisBlock.hash, channelId, node1_priv, node2_priv.publicKey, funding1_priv, funding2_priv.publicKey, ByteVector.empty)
    val (node2_sig, funding2_sig) = signChannelAnnouncement(Block.RegtestGenesisBlock.hash, channelId, node2_priv, node1_priv.publicKey, funding2_priv, funding1_priv.publicKey, ByteVector.empty)
    makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, channelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, node1_sig, node2_sig, funding1_sig, funding2_sig)
  }
}
