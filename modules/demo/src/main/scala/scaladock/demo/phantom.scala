package scaladock.demo

/** A procedural head phantom: a T1-weighted-looking axial slice, a z-statistic map and an atlas
  * outline, all on one 256² grid. Deterministic per (slice, seed); cheap enough to regenerate on
  * every scroll step.
  */
private[demo] object Phantom:
  val Size   = 256
  val Slices = 96

  /** One slice's raw fields; `composite` turns them into display pixels. */
  final class Slice(val anat: Array[Float], val stat: Array[Float], val edge: Array[Boolean])

  private final case class Blob(x: Double, y: Double, z: Double, sigma: Double, peak: Double)

  private val blobs = Vector(
    Blob(0.36, -0.20, 0.05, 0.085, 6.2),
    Blob(-0.34, -0.24, 0.00, 0.075, 5.2),
    Blob(0.03, 0.66, -0.05, 0.100, 4.6),
    Blob(0.20, 0.34, 0.15, 0.060, 3.9)
  )

  private val Sides = Array(-1.0, 1.0)

  /** Shrinks the head inside the field of view so at least ~10% black margin stays clear for the
    * corner annotations.
    */
  private val HeadScale = 0.86

  private def inEllipse(du: Double, dv: Double, a: Double, b: Double, tilt: Double): Boolean =
    val c = math.cos(tilt)
    val s = math.sin(tilt)
    val x = (du * c + dv * s) / a
    val y = (-du * s + dv * c) / b
    x * x + y * y < 1

  def slice(index: Int, seed: Long): Slice =
    val n     = Size
    val noise = Noise(seed)
    val grain = java.util.Random(seed * 131 + index)
    val zn    = (index - Slices / 2.0) / (Slices / 2.0)       // -1 inferior .. +1 superior
    val k     = math.sqrt(math.max(0.02, 1 - math.pow(zn * 0.9, 2)))
    val vf    = math.max(0.0, 1 - math.abs(zn - 0.02) / 0.32) // lateral ventricles
    val deep  = math.max(0.0, 1 - math.abs(zn + 0.05) / 0.30) // basal ganglia / thalamus
    val raw   = new Array[Float](n * n)
    val stat  = new Array[Float](n * n)
    val label = new Array[Int](n * n)

    var j = 0
    while j < n do
      var i = 0
      while i < n do
        val u     = ((i + 0.5) / n * 2 - 1) / HeadScale
        val v     = ((j + 0.5) / n * 2 - 1) / HeadScale
        val idx   = j * n + i
        val wob   = noise.fbm(u * 2.2, v * 2.2)
        val eHead = math.hypot(u / (0.74 * k), v / (0.90 * k)) * (1 + 0.012 * wob)
        val value =
          if eHead > 1 then 0.015
          else if eHead > 0.955 then 0.58 + 0.06 * wob // scalp fat: bright on T1
          else if eHead > 0.915 then 0.09              // cortical bone
          else if eHead > 0.895 then 0.13              // CSF
          else
            val r      = eHead / 0.895
            val theta  = math.atan2(v, u)
            val phi    = theta * 15 + 3.2 * noise.fbm(u * 3 + 7, v * 3 - 3)
            val finger = math.sin(phi)
            val wmEdge = 0.80 + 0.075 * finger
            var t =
              if r > wmEdge then
                if finger < -0.82 && r > wmEdge + 0.025 then 0.14 // sulcal CSF
                else 0.43 + 0.03 * wob                            // cortex
              else 0.76 + 0.02 * wob                              // white matter
            if math.abs(u + 0.01 * wob) < 0.011 && math.abs(v) / k > 0.42 then t = 0.13
            for sx <- Sides do
              if deep > 0 && inEllipse(
                  u - sx * 0.19 * k,
                  v - 0.06 * k,
                  0.085 * k,
                  0.13 * k * deep,
                  sx * 0.3
                )
              then
                t = 0.56
              if vf > 0 && inEllipse(
                  u - sx * 0.085 * k,
                  v + 0.06 * k,
                  0.05 * k * (0.4 + 0.6 * vf),
                  0.21 * k * vf,
                  sx * 0.22
                )
              then t = 0.10
            label(idx) =
              1 + (((theta + math.Pi + 0.25 * wob) / (2 * math.Pi) * 8).toInt & 7) + (if r < 0.5
                                                                                      then 8
                                                                                      else 0)
            var z = 0.0
            for b <- blobs do
              val dx = u - b.x * k
              val dy = v - b.y * k
              val dz = zn - b.z
              z += b.peak * math.exp(
                -(dx * dx + dy * dy) / (2 * b.sigma * b.sigma) - dz * dz / (2 * 0.25 * 0.25)
              )
            stat(idx) = (z * (if r > wmEdge then 1.0 else 0.55) + 0.6 * noise.fbm(
              u * 9 + 11,
              v * 9 + 5
            )).toFloat
            t
        raw(idx) = value.toFloat
        i += 1
      end while
      j += 1
    end while

    // partial-volume blur, a smooth bias field, then Rician-ish grain
    val anat = new Array[Float](n * n)
    val edge = new Array[Boolean](n * n)
    j = 0
    while j < n do
      var i = 0
      while i < n do
        var sum = 0.0
        var dj  = -1
        while dj <= 1 do
          var di = -1
          while di <= 1 do
            val y = (j + dj).max(0).min(n - 1)
            val x = (i + di).max(0).min(n - 1)
            sum += raw(y * n + x)
            di += 1
          dj += 1
        val u    = (i + 0.5) / n * 2 - 1
        val v    = (j + 0.5) / n * 2 - 1
        val bias = 0.94 + 0.08 * noise.fbm(u * 0.8 + 3, v * 0.8 + 9)
        val idx  = j * n + i
        anat(idx) = math.abs(sum / 9 * bias + 0.018 * grain.nextGaussian()).min(1.0).toFloat
        val l = label(idx)
        edge(idx) =
          l != 0 && ((i + 1 < n && label(idx + 1) != l) || (j + 1 < n && label(idx + n) != l))
        i += 1
      j += 1
    Slice(anat, stat, edge)
  end slice

  private val StatLow  = 2.3
  private val StatHigh = 6.0

  private def clamp01(x: Double): Double = if x < 0 then 0 else if x > 1 then 1 else x

  /** The hot colormap (black-red-yellow-white) at `t` in [0, 1], as (r, g, b). */
  def hot(t: Double): (Double, Double, Double) =
    (clamp01(t * 3), clamp01(t * 3 - 1), clamp01(t * 3 - 2))

  /** Blend the fields into ARGB pixels; each weight is an effective opacity (0 when hidden). */
  def composite(s: Slice, out: Array[Int], anat: Double, stat: Double, atlas: Double): Unit =
    var idx = 0
    while idx < out.length do
      val g  = s.anat(idx) * anat
      var r  = g
      var gr = g
      var b  = g
      val z  = s.stat(idx)
      if stat > 0 && z > StatLow then
        val a            = stat * clamp01((z - StatLow) / 0.5)
        val (hr, hg, hb) = hot(0.3 + 0.7 * clamp01((z - StatLow) / (StatHigh - StatLow)))
        r = r * (1 - a) + hr * a
        gr = gr * (1 - a) + hg * a
        b = b * (1 - a) + hb * a
      if atlas > 0 && s.edge(idx) then
        r = r * (1 - atlas) + 0.21 * atlas
        gr = gr * (1 - atlas) + 0.76 * atlas
        b = b * (1 - atlas) + 0.76 * atlas
      out(idx) = 0xff000000 | (channel(r) << 16) | (channel(gr) << 8) | channel(b)
      idx += 1

  private def channel(x: Double): Int = (clamp01(x) * 255 + 0.5).toInt
end Phantom

/** Smooth 2-D value noise with a few octaves: organic wobble without a dependency. */
private final class Noise(seed: Long):
  private val s = (seed ^ (seed >>> 32)).toInt

  private def lattice(ix: Int, iy: Int): Double =
    var h = ix * 374761393 + iy * 668265263 + s * 1442695041
    h = (h ^ (h >>> 13)) * 1274126177
    h = h ^ (h >>> 16)
    (h & 0xffffff) / 8388607.5 - 1.0

  private def value(x: Double, y: Double): Double =
    val x0  = math.floor(x).toInt
    val y0  = math.floor(y).toInt
    val fx  = x - x0
    val fy  = y - y0
    val sx  = fx * fx * (3 - 2 * fx)
    val sy  = fy * fy * (3 - 2 * fy)
    val top = lattice(x0, y0) + (lattice(x0 + 1, y0) - lattice(x0, y0)) * sx
    val bot = lattice(x0, y0 + 1) + (lattice(x0 + 1, y0 + 1) - lattice(x0, y0 + 1)) * sx
    top + (bot - top) * sy

  def fbm(x: Double, y: Double): Double =
    value(x, y) * 0.6 + value(x * 2.03 + 5.1, y * 2.03 - 1.7) * 0.3 + value(
      x * 4.1 - 3.3,
      y * 4.1 + 2.2
    ) * 0.1
