package org.http4s
package multipart

import java.io.{ File, FileInputStream, InputStream }
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.Random

import fs2._
import fs2.Stream._
import fs2.io._
import fs2.text._
import fs2.util.Suspendable
import org.http4s.EntityEncoder._
import org.http4s.MediaType._
import org.http4s.headers._
import org.http4s.util.CaseInsensitiveString
import org.http4s.util.string._

final case class Part[F[_]](headers: Headers, body: EntityBody[F]) {
  def name: Option[CaseInsensitiveString] = headers.get(`Content-Disposition`).map(_.name)
}

object Part {
  private val ChunkSize = 8192

  def empty[F[_]]: Part[F] =
    Part(Headers.empty, EmptyBody)

  def formData[F[_]: Suspendable](name: String, value: String, headers: Header*): Part[F] =
    Part(`Content-Disposition`("form-data", Map("name" -> name)) +: headers,
      emit(value).through(utf8Encode))

  def fileData[F[_]: Suspendable](name: String, file: File, headers: Header*): Part[F] =
    fileData(name, file.getName, new FileInputStream(file), headers:_*)

  def fileData[F[_]: Suspendable](name: String, resource: URL, headers: Header*): Part[F] =
    fileData(name, resource.getPath.split("/").last, resource.openStream(), headers:_*)

  private def fileData[F[_]](name: String, filename: String, in: => InputStream, headers: Header*)(implicit F: Suspendable[F]): Part[F] = {
    Part(`Content-Disposition`("form-data", Map("name" -> name, "filename" -> filename)) +:
           Header("Content-Transfer-Encoding", "binary") +:
           headers,
         readInputStream(F.delay(in), ChunkSize))
   }
}

final case class Multipart[F[_]](parts: Vector[Part[F]], boundary: Boundary = Boundary.create) {
  def headers: Headers = Headers(`Content-Type`(MediaType.multipart("form-data", Some(boundary.value))))
}

final case class Boundary(value: String) extends AnyVal {
  def toChunk: Chunk[Byte] =
    Chunk.bytes(value.getBytes(StandardCharsets.UTF_8))
}

object Boundary {
  private val BoundaryLength = 40
  val CRLF = "\r\n"

  private val DIGIT = ('0' to '9').toList
  private val ALPHA = ('a' to 'z').toList ++ ('A' to 'Z').toList
  private val OTHER = """'()+_,-./:=? """.toSeq
  private val CHARS = (DIGIT ++ ALPHA ++ OTHER).toList
  private val nchars = CHARS.length
  private val rand = new Random()

  private def nextChar = CHARS(rand.nextInt(nchars - 1))
  private def stream: scala.Stream[Char] = scala.Stream continually (nextChar)
  //Don't use filterNot it works for 2.11.4 and nothing else, it will hang.
  private def endChar: Char = stream.filter(_ != ' ').headOption.getOrElse('X')
  private def value(l: Int): String = (stream take l).mkString

  def create: Boundary = Boundary(value(BoundaryLength) + endChar)
}