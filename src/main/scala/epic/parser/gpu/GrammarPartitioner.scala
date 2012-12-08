package epic.parser.gpu

import epic.trees.BinaryRule
import collection.mutable

object GrammarPartitioner {
  sealed trait TargetLabel {
    def clusterPieces[L](r: BinaryRule[L]) = this match {
      case Parent => Set(r.left) -> Set(r.right)
      case LeftChild => Set(r.parent) -> Set(r.right)
      case RightChild => Set(r.parent) -> Set(r.left)
    }

    def target[L](r: BinaryRule[L]) = this match {
      case Parent => r.parent
      case LeftChild => r.left
      case RightChild => r.parent
    }
  }
  case object Parent extends TargetLabel
  case object LeftChild extends TargetLabel
  case object RightChild extends TargetLabel

  def partition(rules: IndexedSeq[(BinaryRule[Int], Int)], maxPartitionLabelSize: Int = 50, targetLabel: TargetLabel = Parent) = {

    case class Partition(targets: Set[Int], group1: Set[Int], group2: Set[Int], isPure: Boolean = true) {
      def merge(p: Partition) = Partition(targets ++ p.targets, group1 ++ p.group1, group2 ++ p.group2, false)

      def tSize = targets.size

      def badness = group1.size + group2.size


      def isTooBig = !isPure && (group1.size + group2.size + targets.size) > maxPartitionLabelSize
    }
    var clusters_x = rules.groupBy(r => targetLabel.target(r._1))
    def restart(random: =>Double) = {
      var clusters = clusters_x.map { case (p:Int, r: IndexedSeq[(BinaryRule[Int], Int)]) =>
        val (g1, g2) = r.map(rr => targetLabel.clusterPieces(rr._1)).unzip
        Partition(Set(p), g1.reduce( _ ++ _), g2.reduce(_ ++ _))
      }.toSet
      val initialClusters = clusters.map(p => p.targets.head -> p).toMap

      def remove(p: Partition, t: Int) = {
        (for(t2 <- p.targets if t != t2) yield initialClusters(t2)).reduceLeft(_ merge _)
      }

      sealed trait Action { def priority: Double}
      case class Merge(p1: Partition, p2: Partition, merged: Partition) extends Action {
        val priority = (p1.badness + p2.badness - merged.badness)*random
      }
      case class SplitMerge(p1: Partition, p2: Partition, t: Int) extends Action {
        val newP1 = remove(p1, t)
        val newP2 = p2 merge initialClusters(t)
        val priority = (p1.badness + p2.badness - newP1.badness - newP2.badness)*random
      }

      implicit val order = Ordering[Double].on[Action](_.priority)

      val queue = new mutable.PriorityQueue[Action]
      queue ++= {for(p1 <- clusters; p2 <- clusters if p1 != p2) yield Merge(p1, p2, p1 merge p2)}

      while(queue.nonEmpty) {
        queue.dequeue() match {
          case sm@Merge(l, r, merger) =>
            if(clusters.contains(l) && clusters.contains(r)) {
              if(!merger.isTooBig) {
                clusters -= l
                clusters -= r
                queue ++= {for(p2 <- clusters) yield Merge(merger, p2, merger merge p2)}
                queue ++= {for(p2 <- clusters; rm  <- merger.targets) yield SplitMerge(merger, p2, rm)}
                clusters += merger

              }
            }
          case sm@SplitMerge(l, r, _) =>
            if(clusters.contains(l) && clusters.contains(r)) {
              import sm._
              if(!newP2.isTooBig) {
                println("Split!")
                clusters -= l
                clusters -= r
                queue ++= {for(p2 <- clusters) yield Merge(newP1, p2, newP1 merge p2)}
                queue ++= {for(p2 <- clusters) yield Merge(newP2, p2, newP2 merge p2)}
                queue ++= {for(p2 <- clusters; rm  <- newP1.targets if newP1.targets.size > 1) yield SplitMerge(newP1, p2, rm)}
                queue ++= {for(p2 <- clusters; rm  <- newP2.targets if newP2.targets.size > 1) yield SplitMerge(newP2, p2, rm)}
                clusters += newP1
                clusters += newP2

              }
            }
        }
      }

      println(clusters.map(_.badness).sum)
      clusters
    }

    val clusters = ((0 until 40).map(new java.util.Random(_)).map(r => restart(0.5 + 0.5 * r.nextDouble()))).minBy(_.map(p => p.badness).sum)

    println("Best badness: " + clusters.map(_.badness).sum)

    var p = 0
    for( Partition(targets, g1, g2, _) <- clusters) {
      println("Partition " + p)
      println("G1: " + g1.size + " " + g1)
      println("G2: " + g2.size + " "  + g2)
      println("targets: " + targets)
      p += 1
    }

    assert(clusters.flatMap(_.targets).toSet.size == clusters_x.keySet.size)
    clusters.map(p => p.targets.flatMap(clusters_x).toIndexedSeq)
  }

}