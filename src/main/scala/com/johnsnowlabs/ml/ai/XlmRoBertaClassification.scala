/*
 * Copyright 2017-2022 John Snow Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.ml.ai

import com.johnsnowlabs.ml.tensorflow.sentencepiece.{SentencePieceWrapper, SentencepieceEncoder}
import com.johnsnowlabs.ml.tensorflow.sign.{ModelSignatureConstants, ModelSignatureManager}
import com.johnsnowlabs.ml.tensorflow.{TensorResources, TensorflowWrapper}
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.annotators.tokenizer.wordpiece.{BasicTokenizer, WordpieceEncoder}
import com.johnsnowlabs.nlp.{ActivationFunction, Annotation}
import org.tensorflow.ndarray.buffer.IntDataBuffer

import scala.collection.JavaConverters._

/** @param tensorflowWrapper
  *   XLM-RoBERTa Model wrapper with TensorFlow Wrapper
  * @param spp
  *   XlmRoberta SentencePiece model with SentencePieceWrapper
  * @param configProtoBytes
  *   Configuration for TensorFlow session
  * @param tags
  *   labels which model was trained with in order
  * @param signatures
  *   TF v2 signatures in Spark NLP
  */
private[johnsnowlabs] class XlmRoBertaClassification(
    val tensorflowWrapper: TensorflowWrapper,
    val spp: SentencePieceWrapper,
    configProtoBytes: Option[Array[Byte]] = None,
    tags: Map[String, Int],
    signatures: Option[Map[String, String]] = None,
    threshold: Float = 0.5f)
    extends Serializable
    with XXXForClassification {

  val _tfXlmRoBertaSignatures: Map[String, String] =
    signatures.getOrElse(ModelSignatureManager.apply())

  protected val sentenceStartTokenId: Int = 0
  protected val sentenceEndTokenId: Int = 2
  protected val sentencePadTokenId: Int = 1

  private val sentencePieceDelimiterId = spp.getSppModel.pieceToId("▁")
  protected val sigmoidThreshold: Float = threshold

  def tokenizeWithAlignment(
      sentences: Seq[TokenizedSentence],
      maxSeqLength: Int,
      caseSensitive: Boolean): Seq[WordpieceTokenizedSentence] = {

    val encoder =
      new SentencepieceEncoder(spp, caseSensitive, sentencePieceDelimiterId, pieceIdOffset = 1)

    val sentenceTokenPieces = sentences.map { s =>
      val trimmedSentence = s.indexedTokens.take(maxSeqLength - 2)
      val wordpieceTokens =
        trimmedSentence.flatMap(token => encoder.encode(token)).take(maxSeqLength)
      WordpieceTokenizedSentence(wordpieceTokens)
    }
    sentenceTokenPieces
  }

  def tokenizeSeqString(
      candidateLabels: Seq[String],
      maxSeqLength: Int,
      caseSensitive: Boolean): Seq[WordpieceTokenizedSentence] = {

    val basicTokenizer = new BasicTokenizer(caseSensitive)
    val encoder =
      new SentencepieceEncoder(spp, caseSensitive, sentencePieceDelimiterId, pieceIdOffset = 1)

    val labelsToSentences = candidateLabels.map { s => Sentence(s, 0, s.length - 1, 0) }

    labelsToSentences.map(label => {
      val tokens = basicTokenizer.tokenize(label)
      val wordpieceTokens = tokens.flatMap(token => encoder.encode(token)).take(maxSeqLength)
      WordpieceTokenizedSentence(wordpieceTokens)
    })
  }
  def tokenizeDocument(
      docs: Seq[Annotation],
      maxSeqLength: Int,
      caseSensitive: Boolean): Seq[WordpieceTokenizedSentence] = {

    val encoder =
      new SentencepieceEncoder(
        spp,
        caseSensitive,
        sentencePieceDelimiterId - 1,
        pieceIdOffset = 1)

    val sentences = docs.map { s => Sentence(s.result, s.begin, s.end, 0) }

    val sentenceTokenPieces = sentences.map { s =>
      val wordpieceTokens = encoder.encodeSentence(s, maxLength = maxSeqLength).take(maxSeqLength)
      WordpieceTokenizedSentence(wordpieceTokens)
    }
    sentenceTokenPieces
  }

  def tag(batch: Seq[Array[Int]]): Seq[Array[Array[Float]]] = {
    val tensors = new TensorResources()

    val maxSentenceLength = batch.map(encodedSentence => encodedSentence.length).max
    val batchLength = batch.length

    val tokenBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)
    val maskBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)

    // [nb of encoded sentences , maxSentenceLength]
    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.zipWithIndex
      .foreach { case (sentence, idx) =>
        val offset = idx * maxSentenceLength
        tokenBuffers.offset(offset).write(sentence)
        maskBuffers
          .offset(offset)
          .write(sentence.map(x => if (x == sentencePadTokenId) 0 else 1))
      }

    val runner = tensorflowWrapper
      .getTFSessionWithSignature(configProtoBytes = configProtoBytes, initAllTables = false)
      .runner

    val tokenTensors = tensors.createIntBufferTensor(shape, tokenBuffers)
    val maskTensors = tensors.createIntBufferTensor(shape, maskBuffers)

    runner
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.InputIds.key, "missing_input_id_key"),
        tokenTensors)
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.AttentionMask.key, "missing_input_mask_key"),
        maskTensors)
      .fetch(_tfXlmRoBertaSignatures
        .getOrElse(ModelSignatureConstants.LogitsOutput.key, "missing_logits_key"))

    val outs = runner.run().asScala
    val rawScores = TensorResources.extractFloats(outs.head)

    outs.foreach(_.close())
    tensors.clearSession(outs)
    tensors.clearTensors()

    val dim = rawScores.length / (batchLength * maxSentenceLength)
    val batchScores: Array[Array[Array[Float]]] = rawScores
      .grouped(dim)
      .map(scores => calculateSoftmax(scores))
      .toArray
      .grouped(maxSentenceLength)
      .toArray

    batchScores
  }

  def tagSequence(batch: Seq[Array[Int]], activation: String): Array[Array[Float]] = {
    val tensors = new TensorResources()

    val maxSentenceLength = batch.map(encodedSentence => encodedSentence.length).max
    val batchLength = batch.length

    val tokenBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)
    val maskBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)

    // [nb of encoded sentences , maxSentenceLength]
    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.zipWithIndex
      .foreach { case (sentence, idx) =>
        val offset = idx * maxSentenceLength
        tokenBuffers.offset(offset).write(sentence)
        maskBuffers
          .offset(offset)
          .write(sentence.map(x => if (x == sentencePadTokenId) 0 else 1))
      }

    val runner = tensorflowWrapper
      .getTFSessionWithSignature(configProtoBytes = configProtoBytes, initAllTables = false)
      .runner

    val tokenTensors = tensors.createIntBufferTensor(shape, tokenBuffers)
    val maskTensors = tensors.createIntBufferTensor(shape, maskBuffers)

    runner
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.InputIds.key, "missing_input_id_key"),
        tokenTensors)
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.AttentionMask.key, "missing_input_mask_key"),
        maskTensors)
      .fetch(_tfXlmRoBertaSignatures
        .getOrElse(ModelSignatureConstants.LogitsOutput.key, "missing_logits_key"))

    val outs = runner.run().asScala
    val rawScores = TensorResources.extractFloats(outs.head)

    outs.foreach(_.close())
    tensors.clearSession(outs)
    tensors.clearTensors()

    val dim = rawScores.length / batchLength
    val batchScores: Array[Array[Float]] =
      rawScores
        .grouped(dim)
        .map(scores =>
          activation match {
            case ActivationFunction.softmax => calculateSoftmax(scores)
            case ActivationFunction.sigmoid => calculateSigmoid(scores)
            case _ => calculateSoftmax(scores)
          })
        .toArray

    batchScores
  }

  def tagZeroShotSequence(
      batch: Seq[Array[Int]],
      entailmentId: Int,
      contradictionId: Int,
      activation: String): Array[Array[Float]] = {
    val tensors = new TensorResources()

    val maxSentenceLength = batch.map(encodedSentence => encodedSentence.length).max
    val batchLength = batch.length

    val tokenBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)
    val maskBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)
    val segmentBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)

    // [nb of encoded sentences , maxSentenceLength]
    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.zipWithIndex
      .foreach { case (sentence, idx) =>
        val offset = idx * maxSentenceLength
        tokenBuffers.offset(offset).write(sentence)
        maskBuffers.offset(offset).write(sentence.map(x => if (x == 0) 0 else 1))
        val sentenceEndTokenIndex = sentence.indexOf(sentenceEndTokenId)
        segmentBuffers
          .offset(offset)
          .write(
            sentence.indices
              .map(i =>
                if (i < sentenceEndTokenIndex) 0
                else if (i == sentenceEndTokenIndex) 1
                else 1)
              .toArray)
      }

    val session = tensorflowWrapper.getTFSessionWithSignature(
      configProtoBytes = configProtoBytes,
      savedSignatures = signatures,
      initAllTables = false)
    val runner = session.runner

    val tokenTensors = tensors.createIntBufferTensor(shape, tokenBuffers)
    val maskTensors = tensors.createIntBufferTensor(shape, maskBuffers)
    val segmentTensors = tensors.createIntBufferTensor(shape, segmentBuffers)

    runner
      .feed(
        _tfXlmRoBertaSignatures.getOrElse(
          ModelSignatureConstants.InputIds.key,
          "missing_input_id_key"),
        tokenTensors)
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.AttentionMask.key, "missing_input_mask_key"),
        maskTensors)
      .fetch(_tfXlmRoBertaSignatures
        .getOrElse(ModelSignatureConstants.LogitsOutput.key, "missing_logits_key"))

    val outs = runner.run().asScala
    val rawScores = TensorResources.extractFloats(outs.head)

    outs.foreach(_.close())
    tensors.clearSession(outs)
    tensors.clearTensors()

    val dim = rawScores.length / batchLength
    rawScores
      .grouped(dim)
      .toArray
  }

  def tagSpan(batch: Seq[Array[Int]]): (Array[Array[Float]], Array[Array[Float]]) = {
    val tensors = new TensorResources()

    val maxSentenceLength = batch.map(encodedSentence => encodedSentence.length).max
    val batchLength = batch.length

    val tokenBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)
    val maskBuffers: IntDataBuffer = tensors.createIntBuffer(batchLength * maxSentenceLength)

    // [nb of encoded sentences , maxSentenceLength]
    val shape = Array(batch.length.toLong, maxSentenceLength)

    batch.zipWithIndex
      .foreach { case (sentence, idx) =>
        val offset = idx * maxSentenceLength
        tokenBuffers.offset(offset).write(sentence)
        maskBuffers
          .offset(offset)
          .write(sentence.map(x => if (x == sentencePadTokenId) 0 else 1))
      }

    val runner = tensorflowWrapper
      .getTFSessionWithSignature(configProtoBytes = configProtoBytes, initAllTables = false)
      .runner

    val tokenTensors = tensors.createIntBufferTensor(shape, tokenBuffers)
    val maskTensors = tensors.createIntBufferTensor(shape, maskBuffers)

    runner
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.InputIds.key, "missing_input_id_key"),
        tokenTensors)
      .feed(
        _tfXlmRoBertaSignatures
          .getOrElse(ModelSignatureConstants.AttentionMask.key, "missing_input_mask_key"),
        maskTensors)
      .fetch(_tfXlmRoBertaSignatures
        .getOrElse(ModelSignatureConstants.EndLogitsOutput.key, "missing_end_logits_key"))
      .fetch(_tfXlmRoBertaSignatures
        .getOrElse(ModelSignatureConstants.StartLogitsOutput.key, "missing_start_logits_key"))

    val outs = runner.run().asScala
    val endLogits = TensorResources.extractFloats(outs.head)
    val startLogits = TensorResources.extractFloats(outs.last)

    outs.foreach(_.close())
    tensors.clearSession(outs)
    tensors.clearTensors()

    val endDim = endLogits.length / batchLength
    val endScores: Array[Array[Float]] =
      endLogits.grouped(endDim).map(scores => calculateSoftmax(scores)).toArray

    val startDim = startLogits.length / batchLength
    val startScores: Array[Array[Float]] =
      startLogits.grouped(startDim).map(scores => calculateSoftmax(scores)).toArray

    (startScores, endScores)
  }

  def findIndexedToken(
      tokenizedSentences: Seq[TokenizedSentence],
      sentence: (WordpieceTokenizedSentence, Int),
      tokenPiece: TokenPiece): Option[IndexedToken] = {
    tokenizedSentences(sentence._2).indexedTokens.find(p =>
      p.begin == tokenPiece.begin && tokenPiece.isWordStart)
  }

}
