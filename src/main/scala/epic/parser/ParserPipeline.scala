package epic.parser
/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import projections.ParserChartConstraintsFactory
import breeze.config._
import java.io._
import epic.trees._
import breeze.util._
import epic.parser
import collection.mutable
import epic.lexicon.{Lexicon, SimpleLexicon}
import epic.constraints.{CachedChartConstraintsFactory, ChartConstraints}
import epic.util.CacheBroker
import com.typesafe.scalalogging.log4j.Logging


/**
 * Mostly a utility class for parsertrainers.
 */
object ParserParams {
  case class JointParams[M](treebank: ProcessedTreebank,
                            trainer: M,
                            @Help(text="Print this and exit.") help: Boolean = false,
                            @Help(text="Default number of threads to use. Default is as many as possible.") threads: Int = -1)

  @Help(text="Stores/loads a baseline xbar grammar needed to extracting trees.")
  case class XbarGrammar(path: File = new File("xbar.gr")) {
    def xbarGrammar(trees: IndexedSeq[TreeInstance[AnnotatedLabel, String]]) = Option(path) match {
      case Some(f) if f.exists =>
        XbarGrammar.cache.getOrElseUpdate(f, readObject[(parser.BaseGrammar[AnnotatedLabel],Lexicon[AnnotatedLabel, String])](f))
      case _ =>
        val (words, xbarBinaries, xbarUnaries) = GenerativeParser.extractCounts(trees.map(_.mapLabels(_.baseAnnotatedLabel)))

        val g = BaseGrammar(AnnotatedLabel.TOP, xbarBinaries.keysIterator.map(_._2) ++ xbarUnaries.keysIterator.map(_._2))
        val lex = new SimpleLexicon(g.labelIndex, words)
        if(path ne null)
          writeObject(path, g -> lex)
        g -> lex

    }
  }

  object XbarGrammar {
    private val cache = new mutable.HashMap[File, (parser.BaseGrammar[AnnotatedLabel],Lexicon[AnnotatedLabel, String])]() with mutable.SynchronizedMap[File, (parser.BaseGrammar[AnnotatedLabel],Lexicon[AnnotatedLabel, String])]
  }

}

/**
 * ParserPipeline is a base-trait for the parser training pipeline. Handles
 * reading in the treebank and params and such
 */
trait ParserPipeline extends Logging {
  /**
   * The type of the parameters to read in via dlwh.epic.config
   */
  type Params <: { def threads: Int}
  /**
   * Required manifest for the params
   */
  protected implicit val paramManifest: Manifest[Params]

  /**
   * The main point of entry for implementors. Should return a sequence
   * of parsers
   */
  def trainParser(trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]],
                  validate: Parser[AnnotatedLabel, String]=>ParseEval.Statistics,
                  params: Params):Iterator[(String, Parser[AnnotatedLabel, String])]


  def trainParser(treebank: ProcessedTreebank, params: Params):Iterator[(String, Parser[AnnotatedLabel, String])] = {
    import treebank._


    val validateTrees = devTrees.take(400)
    def validate(parser: Parser[AnnotatedLabel, String]) = {
      ParseEval.evaluate[AnnotatedLabel](validateTrees, parser, AnnotatedLabelChainReplacer, asString={(l:AnnotatedLabel)=>l.label}, nthreads=params.threads)
    }
    val parsers = trainParser(trainTrees, validate, params)
    parsers
  }

  /**
   * Trains a sequence of parsers and evaluates them.
   */
  def main(args: Array[String]) {
    import ParserParams.JointParams

    val params = CommandLineParser.readIn[JointParams[Params]](args)
    logger.info("Command line arguments for recovery:\n" + Configuration.fromObject(params).toCommandLineString)
    logger.info("Training Parser...")

    val parsers = trainParser(params.treebank, params.trainer)

    import params.treebank._

    for((name, parser) <- parsers) {
      logger.info("Parser " + name)
      val outDir = new File("parsers/")
      outDir.mkdirs()
      val out = new File(outDir, name +".parser")
      writeObject(out, parser)

      logger.info("Evaluating Parser...")
      val stats = evalParser(devTrees.filter(_.words.length <= 40), parser, name+"-len40-dev")
      logger.info(s"Eval finished. Results:\n$stats")
    }
  }

  def evalParser(testTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]],
                 parser: Parser[AnnotatedLabel, String],
                 name: String):ParseEval.Statistics = {
    ParseEval.evaluateAndLog(testTrees, parser, name, AnnotatedLabelChainReplacer, { (_: AnnotatedLabel).label })
  }

}
