package io.github.ticofab.jev

/** The image formats Jev accepts. Still images only: an animated PNG is rejected by the API. */
sealed abstract class ImageFormat(val wire: String)

object ImageFormat {
  case object Png  extends ImageFormat("png")
  case object Jpeg extends ImageFormat("jpg")
}

/**
 * An image attached to a request, sent base64-encoded in the request's `assets`.
 *
 * Images are a TypeSafe "danger zone" feature: supported from `jev-1.13.0`, and not yet held to production standards.
 * See [[JevRequest.withImage]] for how an image is referenced from the state and the questions.
 *
 * Built from raw bytes, an image is always well-formed base64. Built from a string with [[Image.fromBase64]], it is
 * checked when the request carrying it is sent, like everything else -- see [[JevRequest.validate]]. Pixel dimensions
 * are left to the server, which would otherwise mean decoding every image locally.
 */
final class Image private (val format: ImageFormat, val base64: String) {

  /** The size of the decoded image, in bytes. */
  def sizeInBytes: Long = {
    val padding = base64.reverseIterator.take(2).count(_ == '=')
    base64.length.toLong / 4 * 3 - padding
  }

  private[jev] def problems(ref: String): List[String] = {
    val where = s"image '$ref'"
    if (base64.isEmpty) List(s"$where is empty")
    else if (!Image.isBase64(base64))
      List(s"$where is not standard base64 (no whitespace, no 'data:' prefix, '=' padding)")
    else if (sizeInBytes > Image.MaxBytes) List(s"$where is $sizeInBytes bytes, the maximum is ${Image.MaxBytes}")
    else Nil
  }

  private[jev] def toJson: ujson.Value =
    ujson.Obj("type" -> ujson.Str("image"), "format" -> ujson.Str(format.wire), "data_base64" -> ujson.Str(base64))

  override def equals(other: Any): Boolean = other match {
    case that: Image => format == that.format && base64 == that.base64
    case _           => false
  }

  override def hashCode: Int = (format, base64).##

  /** Never prints the data, which can run to megabytes. */
  override def toString: String = s"Image($format, $sizeInBytes bytes)"
}

object Image {

  /** The API's limit for a single image file: 16 MiB. */
  val MaxBytes: Long = 16L * 1024 * 1024

  def png(bytes: Array[Byte]): Image  = fromBytes(ImageFormat.Png, bytes)
  def jpeg(bytes: Array[Byte]): Image = fromBytes(ImageFormat.Jpeg, bytes)

  def fromBytes(format: ImageFormat, bytes: Array[Byte]): Image =
    new Image(format, java.util.Base64.getEncoder.encodeToString(bytes))

  /** An image that is already base64-encoded: standard alphabet, `=` padding, no line breaks, no `data:` prefix. */
  def fromBase64(format: ImageFormat, base64: String): Image =
    new Image(format, if (base64 == null) "" else base64)

  /** Checks the alphabet and padding without decoding, which for a 16 MiB image would mean allocating one. */
  private def isBase64(value: String): Boolean = {
    val padding                 = value.reverseIterator.take(2).count(_ == '=')
    val body                    = value.length - padding
    def valid(c: Char): Boolean =
      (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'

    value.length % 4 == 0 && (0 until body).forall(i => valid(value.charAt(i)))
  }
}
