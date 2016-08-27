package com.btcontract.wallet.lightning

import com.btcontract.wallet.Utils.Bytes
import com.btcontract.wallet.lightning.crypto.ShaChain.HashesWithLastIndex
import crypto.ShaChain
import org.bitcoinj.core.{TransactionOutput, Transaction, Sha256Hash, ECKey}
import com.softwaremill.quicklens._
import Tools._


trait ChannelData {
  // Third Boolean indicates whether it was saved remotely
  type ChannelSnapshot = (List[Symbol], ChannelData, Boolean)
}

// STATIC CHANNEL PARAMETERS

case class TheirChannelParams(delay: Int, commitPubKey: ECKey, finalPubKey: ECKey, minDepth: Int, initialFeeRate: Long)
case class OurChannelParams(delay: Int, anchorAmount: Option[Long], commitPrivKey: ECKey, finalPrivKey: ECKey, minDepth: Int,
                            initialFeeRate: Long, shaSeed: Bytes) extends ChannelData {

  def finalPubKey = ECKey fromPublicOnly finalPrivKey.getPubKey
  def commitPubKey = ECKey fromPublicOnly commitPrivKey.getPubKey

  def toOpenProto(anchorOfferProto: proto.open_channel.anchor_offer) = new proto.open_channel(blocks2Locktime(delay),
    Tools bytes2Sha ShaChain.revIndexFromSeed(shaSeed, 0), Tools bytes2Sha ShaChain.revIndexFromSeed(shaSeed, 1),
    bytes2ProtoPubkey(commitPrivKey.getPubKey), bytes2ProtoPubkey(finalPrivKey.getPubKey),
    anchorOfferProto, minDepth, initialFeeRate)
}

// CURRENT CHANNEL STATE

// Changes pile up until we update our commitment
case class TheirChanges(proposed: PktVec, acked: PktVec)
case class OurChanges(proposed: PktVec, signed: PktVec, acked: PktVec)

// We need to track our commit and our view of their commit
// In case if they spend their commit we can use ours to claim the funds
case class OurCommit(index: Long, spec: CommitmentSpec, publishableTx: Transaction)
case class TheirCommit(index: Long, spec: CommitmentSpec, revocationHash: proto.sha256_hash) {
  def preimageMatch(revPreImage: proto.sha256_hash) = pre2HashProto(revPreImage) == revocationHash
}

// Incoming: they are paying, outgoing: we are paying
case class Htlc(add: proto.update_add_htlc, incoming: Boolean)
case class CommitmentSpec(htlcs: Set[Htlc], feeRate: Long, initAmountUsMsat: Long,
                          initAmountThemMsat: Long, amountUsMsat: Long, amountThemMsat: Long) { me =>

  def addIncomingHtlc(their: proto.pkt) =
    copy(htlcs = htlcs + Htlc(add = their.update_add_htlc, incoming = true),
      amountThemMsat = amountThemMsat - their.update_add_htlc.amount_msat)

  def addOutgoingHtlc(our: proto.pkt) =
    copy(htlcs = htlcs + Htlc(add = our.update_add_htlc, incoming = false),
      amountUsMsat = amountUsMsat - our.update_add_htlc.amount_msat)

  def fulfillHtlc(ufh: proto.update_fulfill_htlc) =
    htlcs.find(htlc => ufh.id == htlc.add.id && r2HashProto(ufh.r) == htlc.add.r_hash) match {
      case Some(htlc) if htlc.incoming => copy(amountUsMsat = amountUsMsat + htlc.add.amount_msat, htlcs = htlcs - htlc)
      case Some(htlc) => copy(amountThemMsat = amountThemMsat + htlc.add.amount_msat, htlcs = htlcs - htlc)
      case _ => me
    }

  def failHtlc(fail: proto.update_fail_htlc) = htlcs.find(fail.id == _.add.id) match {
    case Some(htlc) if htlc.incoming => copy(amountThemMsat = amountThemMsat + htlc.add.amount_msat, htlcs = htlcs - htlc)
    case Some(htlc) => copy(amountUsMsat = amountUsMsat + htlc.add.amount_msat, htlcs = htlcs - htlc)
    case _ => me
  }

  def reduce(ourChanges: PktVec, theirChanges: PktVec) = {
    val spec1 = (me /: ourChanges) { case (s, pkt) if has(pkt.update_add_htlc) => s addOutgoingHtlc pkt case (s, _) => s }
    val spec2 = (spec1 /: theirChanges) { case (s, pkt) if has(pkt.update_add_htlc) => s addIncomingHtlc pkt case (s, _) => s }

    val spec3 = (ourChanges ++ theirChanges).foldLeft(spec2) {
      case (s, pkt) if has(pkt.update_fulfill_htlc) => s fulfillHtlc pkt.update_fulfill_htlc
      case (s, pkt) if has(pkt.update_fail_htlc) => s failHtlc pkt.update_fail_htlc
      case (s, _) => s
    }

    spec3
  }
}

case class Commitments(ourParams: OurChannelParams, theirParams: TheirChannelParams, ourChanges: OurChanges, theirChanges: TheirChanges,
                       ourCommit: OurCommit, theirCommit: TheirCommit, theirNextCommitInfo: Either[TheirCommit, proto.sha256_hash],
                       anchorOutput: TransactionOutput, anchorId: String, theirPreimages: HashesWithLastIndex = (None, Map.empty),
                       started: Long = System.currentTimeMillis, shutdownStarted: Option[Long] = None) { me =>

  def addOurProposal(proposal: proto.pkt) = me.modify(_.ourChanges.proposed).using(_ :+ proposal)
  def addTheirProposal(proposal: proto.pkt) = me.modify(_.theirChanges.proposed).using(_ :+ proposal)
  def finalFee = anchorOutput.getValue subtract ourCommit.publishableTx.getOutputSum div 4 multiply 2
}