package scaladock

/** Toolkit-free geometry primitives. The core never imports JavaFX. */
final case class Point(x: Double, y: Double) derives CanEqual

final case class Rect(x: Double, y: Double, width: Double, height: Double) derives CanEqual:
  def right: Double  = x + width
  def bottom: Double = y + height
  def area: Double   = width * height

  def contains(p: Point): Boolean =
    p.x >= x && p.x < right && p.y >= y && p.y < bottom

object Rect:
  val zero: Rect = Rect(0, 0, 0, 0)

enum Axis derives CanEqual:
  case Horizontal, Vertical

  def cross: Axis = this match
    case Horizontal => Vertical
    case Vertical   => Horizontal

enum Edge derives CanEqual:
  case Left, Right, Top, Bottom

  /** The axis of the split this edge induces: docking Left/Right splits horizontally. */
  def axis: Axis = this match
    case Left | Right => Axis.Horizontal
    case Top | Bottom => Axis.Vertical

  /** Whether the docked item lands before (Left/Top) or after (Right/Bottom) the target. */
  def leading: Boolean = this match
    case Left | Top     => true
    case Right | Bottom => false

/** A child's share of its split's main axis. Default is `1.fr`. */
enum Size derives CanEqual:
  case Fr(weight: Double)
  case Pct(value: Double)
  case Px(value: Double)

object Size:
  extension (n: Int)
    def fr: Size  = Fr(n.toDouble)
    def pct: Size = Pct(n.toDouble)
    def px: Size  = Px(n.toDouble)
  extension (n: Double)
    def fr: Size  = Fr(n)
    def pct: Size = Pct(n)
    def px: Size  = Px(n)
