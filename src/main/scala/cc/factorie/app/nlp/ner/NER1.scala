package cc.factorie.app.nlp.ner
import cc.factorie._
import cc.factorie.app.nlp._
import java.io.File
import cc.factorie.util.{BinarySerializer, CubbieConversions}

/** A finite-state named entity recognizer, trained on CoNLL 2003 data.
    Features include context aggregation and lexicons.
    Trained by online stochastic gradient ascent with an L1 prior that leads to ~90% sparsity.
    Inference by Viterbi.
    Achieves ~91% field F1 on CoNLL 2003 dev set. */
class NER1 extends DocumentAnnotator {
  def this(file: File) = { this(); deserialize(file) }
  def this(url:java.net.URL) = {
    this()
    // TODO I would really like to register a "classpath" URL handler, as in 
    // http://stackoverflow.com/questions/861500/url-to-load-resources-from-the-classpath-in-java
    val stream = url.openConnection.getInputStream //getClass.getResourceAsStream(url.getPath)
    require(stream ne null, "Not found: "+url)
    //if (url.getProtocol == "classpath") deserialize(stream) // But deserializing from an InputStream isn't yet working
    deserialize(stream)
  }

  object FeaturesDomain extends CategoricalTensorDomain[String]
  class FeaturesVariable(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = FeaturesDomain
    override def skipNonCategories = true
  } 
  
  // The model
  val model1 = new TemplateModel with Parameters {
    // Bias term on each individual label
    val bias = this += new DotTemplateWithStatistics1[BilouConllNerLabel] {
      override def neighborDomain1 = BilouConllNerDomain
      val weights = Weights(new la.DenseTensor1(BilouConllNerDomain.size))
    }
    // Transition factors between two successive labels
    val markov = this += new DotTemplateWithStatistics2[BilouConllNerLabel, BilouConllNerLabel] {
      override def neighborDomain1 = BilouConllNerDomain
      override def neighborDomain2 = BilouConllNerDomain
      val weights = Weights(new la.DenseTensor2(BilouConllNerDomain.size, BilouConllNerDomain.size))
      def unroll1(label:BilouConllNerLabel) = if (label.token.sentenceHasPrev) Factor(label.token.prev.attr[BilouConllNerLabel], label) else Nil
      def unroll2(label:BilouConllNerLabel) = if (label.token.sentenceHasNext) Factor(label, label.token.next.attr[BilouConllNerLabel]) else Nil
    }
    // Factor between label and observed token
    val evidence = this += new DotTemplateWithStatistics2[BilouConllNerLabel, FeaturesVariable] {
      override def neighborDomain1 = BilouConllNerDomain
      override def neighborDomain2 = FeaturesDomain
      val weights = Weights(new la.DenseTensor2(BilouConllNerDomain.size, FeaturesDomain.dimensionSize))
      def unroll1(label:BilouConllNerLabel) = Factor(label, label.token.attr[FeaturesVariable])
      def unroll2(token:FeaturesVariable) = throw new Error("FeaturesVariable values shouldn't change")
    }
    // More efficient unrolling if given the sequence of labels
    override def factors(vars:Iterable[Var]): Iterable[Factor] = vars match {
      case vars:Seq[BilouConllNerLabel] if vars.forall(_.isInstanceOf[BilouConllNerLabel]) => {
        val result = new scala.collection.mutable.ArrayBuffer[Factor](vars.length*3)
        var prev: BilouConllNerLabel = null
        for (v <- vars) {
          result += bias.Factor(v); result += evidence.Factor(v, v.token.attr[FeaturesVariable])
          if (prev ne null) result += markov.Factor(prev, v)
          prev = v
        }
        result
      }
      case _ => super.factors(vars)
    }
  }
  
  //val model2 = new cc.factorie.app.chain.ChainModel[BilouConllNerLabel,FeaturesVariable,Token](BilouConllNerDomain, FeaturesDomain, l=>l.token.attr[FeaturesVariable], l=>l.token, t=>t.attr[BilouConllNerLabel])
  val model = model1
  
  
  // The training objective
  val objective = new HammingTemplate[BilouConllNerLabel]

  // Methods of DocumentAnnotator
  override def tokenAnnotationString(token:Token): String = token.attr[BilouConllNerLabel].categoryValue
  def prereqAttrs: Iterable[Class[_]] = List(classOf[Token])
  def postAttrs: Iterable[Class[_]] = List(classOf[BilouConllNerLabel])
  def process1(document:Document): Document = {
    if (document.tokenCount > 0) {
      val alreadyHadFeatures = document.hasAnnotation(classOf[FeaturesVariable])
      if (!alreadyHadFeatures) addFeatures(document)
      for (token <- document.tokens) if (token.attr[BilouConllNerLabel] eq null) token.attr += new BilouConllNerLabel(token, "O")
      for (sentence <- document.sentences if sentence.length > 0)
        BP.inferChainMax(sentence.tokens.map(_.attr[BilouConllNerLabel]).toSeq, model)
      if (!alreadyHadFeatures) { document.annotators.remove(classOf[FeaturesVariable]); for (token <- document.tokens) token.attr.remove[FeaturesVariable] }
    }
    document
  }
  
  // For words like Swedish & Swedes but not Sweden
  object Demonyms extends lexicon.PhraseLexicon("iesl/demonyms") { 
    for (line <- io.Source.fromInputStream(lexicon.ClasspathResourceLexicons.getClass.getResourceAsStream("iesl/demonyms.txt")).getLines) {
      val fields = line.trim.split(" ?\t ?") // TODO The currently checked in version has extra spaces in it; when this is fixed, use simply: ('\t')
      for (phrase <- fields.drop(1)) this += phrase
    }
  }
  
  // Feature creation
  def addFeatures(document:Document): Unit = {
    document.annotators(classOf[FeaturesVariable]) = this
    import cc.factorie.app.strings.simplifyDigits
    for (token <- document.tokens) {
      val features = new FeaturesVariable(token)
      token.attr += features
      val rawWord = token.string
      val word = simplifyDigits(rawWord).toLowerCase
      features += "W="+word
      features += "SHAPE="+cc.factorie.app.strings.stringShape(rawWord, 2)
      if (token.isPunctuation) features += "PUNCTUATION"
      if (lexicon.iesl.PersonFirst.containsLemmatizedWord(word)) features += "PERSON-FIRST"
      if (lexicon.iesl.Month.containsLemmatizedWord(word)) features += "MONTH"
      if (lexicon.iesl.PersonLast.containsLemmatizedWord(word)) features += "PERSON-LAST"
      if (lexicon.iesl.PersonHonorific.containsLemmatizedWord(word)) features += "PERSON-HONORIFIC"
      if (lexicon.iesl.Company.contains(token)) features += "COMPANY"
      if (lexicon.iesl.Country.contains(token)) features += "COUNTRY"
      if (lexicon.iesl.City.contains(token)) features += "CITY"
      if (lexicon.iesl.PlaceSuffix.contains(token)) features += "PLACE-SUFFIX"
      if (lexicon.iesl.USState.contains(token)) features += "USSTATE"
      if (lexicon.wikipedia.Person.contains(token)) features += "WIKI-PERSON"
      if (lexicon.wikipedia.Event.contains(token)) features += "WIKI-EVENT"
      if (lexicon.wikipedia.Location.contains(token)) features += "WIKI-LOCATION"
      if (lexicon.wikipedia.Organization.contains(token)) features += "WIKI-ORG"
      if (lexicon.wikipedia.ManMadeThing.contains(token)) features += "MANMADE"
      if (lexicon.wikipedia.Event.contains(token)) features += "EVENT"
      if (Demonyms.contains(token)) features += "DEMONYM"
    }
    //for (sentence <- document.sentences) cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(sentence.tokens, (t:Token)=>t.attr[FeaturesVariable], List(0), List(0,0), List(0,0,-1), List(0,0,1), List(1), List(2), List(-1), List(-2))
    //for (sentence <- document.sentences) cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(sentence.tokens, (t:Token)=>t.attr[FeaturesVariable], List(0), List(1), List(2), List(-1), List(-2))
    for (sentence <- document.sentences) cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(sentence.tokens, (t:Token)=>t.attr[FeaturesVariable], List(0), List(1), List(2), List(-1), List(-2), List(0,-1), List(0,1))

    for (token <- document.tokens) {
      val word = cc.factorie.app.strings.simplifyDigits(token.string).toLowerCase
      val features = token.attr[FeaturesVariable]
      if (word.length > 5) { features += "P="+cc.factorie.app.strings.prefix(word, 4); features += "S="+cc.factorie.app.strings.suffix(word, 4) }
    }

    // Add features from window of 4 words before and after
    document.tokens.foreach(t => t.attr[FeaturesVariable] ++= t.prevWindow(4).map(t2 => "PREVWINDOW="+simplifyDigits(t2.string).toLowerCase))
    document.tokens.foreach(t => t.attr[FeaturesVariable] ++= t.nextWindow(4).map(t2 => "NEXTWINDOW="+simplifyDigits(t2.string).toLowerCase))
    // Put features of first mention on later mentions
    document.tokens.foreach(t => {
      if (t.isCapitalized && t.string.length > 1 && !t.attr[FeaturesVariable].activeCategories.exists(f => f.matches(".*FIRSTMENTION.*"))) {
        //println("Looking for later mentions of "+t.word)
        var t2 = t
        while (t2.hasNext) {
          t2 = t2.next
          if (t2.string == t.string) { 
            //println("Adding FIRSTMENTION to "+t2.word); 
            t2.attr[FeaturesVariable] ++= t.attr[FeaturesVariable].activeCategories.map(f => "FIRSTMENTION="+f) // Only put the @ context features in, not the others.
          }
        }
      }
    })
    //for (section <- document.sections) cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(section.tokens, (t:Token)=>t.attr[FeaturesVariable], Seq(0), Seq(-1), Seq(-2), Seq(1), Seq(2), Seq(0,0))
  }

  def tokenFeaturesString(tokens:Iterable[Token]): String = tokens.map(token => "%-20s  %s".format(token.string, token.attr[FeaturesVariable])).mkString("\n")
  
  def sampleOutputString(tokens:Iterable[Token]): String = {
    val sb = new StringBuffer
    for (token <- tokens) sb.append("%s %20s %10s %10s  %s\n".format(if (token.attr[BilouConllNerLabel].valueIsTarget) " " else "*", token.string, token.attr[BilouConllNerLabel].target.categoryValue, token.attr[BilouConllNerLabel].categoryValue, token.attr[FeaturesVariable]))
    sb.toString
  }

  // Parameter estimation
  def train(trainDocs:Iterable[Document], testDocs:Iterable[Document], l1Factor:Double = 0.02, l2Factor:Double = 000001): Unit = {
    def labels(docs:Iterable[Document]): Iterable[BilouConllNerLabel] = docs.flatMap(doc => doc.tokens.map(_.attr[BilouConllNerLabel]))
    trainDocs.foreach(addFeatures(_)); FeaturesDomain.freeze(); testDocs.foreach(addFeatures(_)) // Discovery features on training data only
    println(sampleOutputString(trainDocs.take(12).last.tokens.take(200)))
    val trainLabels = labels(trainDocs).toIndexedSeq
    val testLabels = labels(testDocs).toIndexedSeq
    model.limitDiscreteValuesAsIn(trainLabels)
    val examples = trainDocs.flatMap(_.sentences.map(sentence => new optimize.LikelihoodExample(sentence.tokens.map(_.attr[BilouConllNerLabel]), model, InferByBPChainSum))).toSeq
    val trainer = new optimize.OnlineTrainer(model.parameters, new optimize.AdaGradRDA(l1=l1Factor/examples.length, l2=l2Factor/examples.length)) // L2RegularizedConcentrate or AdaMIRA (gives smaller steps)
    //val trainer = new optimize.OnlineTrainer(model.parameters)
    for (iteration <- 1 until 4) { 
      trainer.processExamples(examples)
      trainDocs.foreach(process(_))
      println("Train accuracy "+objective.accuracy(trainLabels))
      println(new app.chain.SegmentEvaluation[BilouConllNerLabel]("(B|U)-", "(I|L)-", BilouConllNerDomain, trainLabels.toIndexedSeq))
      if (!testDocs.isEmpty) { 
        testDocs.foreach(process(_))
        println("Test  accuracy "+objective.accuracy(testLabels))
        println(new app.chain.SegmentEvaluation[BilouConllNerLabel]("(B|U)-", "(I|L)-", BilouConllNerDomain, testLabels.toIndexedSeq))
      }
      println(model.parameters.tensors.sumInts(t => t.toSeq.count(x => x == 0)).toFloat/model.parameters.tensors.sumInts(_.length)+" sparsity")
    }
    return    // TODO Don't bother LBFGS training
    val trainer2 = new optimize.BatchTrainer(model.parameters, new optimize.LBFGS with optimize.L2Regularization { variance = 10.0 })
    while (!trainer2.isConverged) {
      trainer2.processExamples(examples)
      trainDocs.foreach(process(_)); println("Train accuracy "+objective.accuracy(trainLabels))
      if (!testDocs.isEmpty) testDocs.foreach(process(_));  println("Test  accuracy "+objective.accuracy(testLabels))
      println(new app.chain.SegmentEvaluation[BilouConllNerLabel]("(B|U)-", "(I|L)-", BilouConllNerDomain, testLabels.toIndexedSeq))
    }
    new java.io.PrintStream(new File("ner3-test-output")).print(sampleOutputString(testDocs.flatMap(_.tokens)))
  }
  def loadDocuments(files:Iterable[File]): Seq[Document] = {
    def fixLabels(docs:Seq[Document]): Seq[Document] = { for (doc <- docs; token <- doc.tokens) token.attr += new BilouConllNerLabel(token, token.attr[NerLabel].categoryValue); docs }
    fixLabels(files.toSeq.flatMap(file => LoadConll2003(BILOU=true).fromFile(file)))
  }
  
  // Serialization
  def serialize(filename: String): Unit = {
    val file = new File(filename); if (file.getParentFile eq null) file.getParentFile.mkdirs()
    serialize(new java.io.FileOutputStream(file))
  }
  def deserialize(file: File): Unit = {
    require(file.exists(), "Trying to load non-existent file: '" +file)
    deserialize(new java.io.FileInputStream(file))
  }
  def serialize(stream: java.io.OutputStream): Unit = {
    import CubbieConversions._
    val dstream = new java.io.DataOutputStream(stream)
    BinarySerializer.serialize(FeaturesDomain.dimensionDomain, dstream)
    BinarySerializer.serialize(model, dstream)
    dstream.close()  // TODO Are we really supposed to close here, or is that the responsibility of the caller
  }
  def deserialize(stream: java.io.InputStream): Unit = {
    import CubbieConversions._
    val dstream = new java.io.DataInputStream(stream)
    BinarySerializer.deserialize(FeaturesDomain.dimensionDomain, dstream)
    BinarySerializer.deserialize(model, dstream)
    model.parameters.densify()
    dstream.close()  // TODO Are we really supposed to close here, or is that the responsibility of the caller
  }
}

/** Main function for training NER1 from CoNLL 2003 data. */
object NER1 {
  def main(args:Array[String]): Unit = {
    object opts extends cc.factorie.util.DefaultCmdOptions {
      val saveModel = new CmdOption("save-model", "NER1.factorie", "FILENAME", "Filename for the model (saving a trained model or reading a running model.")
      val train = new CmdOption("train", List("eng.train"), "FILENAME..", "Filename(s) from which to read training data in CoNLL 2003 one-word-per-lineformat.")
      val test = new CmdOption("test", List("eng.testa"), "FILENAME...", "Filename(s) from which to read test data in CoNLL 2003 one-word-per-lineformat.")
      val l1 = new CmdOption("l1", 0.02, "FLOAT", "L1 regularizer for AdaGradRDA training.")
      val l2 = new CmdOption("l2", 0.000001, "FLOAT", "L2 regularizer for AdaGradRDA training.")
    }
    opts.parse(args)
    
    val ner = new NER1
    
    if (opts.train.wasInvoked) {
      ner.train(ner.loadDocuments(opts.train.value.map(new File(_))), ner.loadDocuments(opts.test.value.map(new File(_))), opts.l1.value, opts.l2.value)
      if (opts.saveModel.wasInvoked) ner.serialize(opts.saveModel.value)
    } else {
      System.err.println(getClass.toString+" : --train argument required.")
      System.exit(-1)
    }
  }
}