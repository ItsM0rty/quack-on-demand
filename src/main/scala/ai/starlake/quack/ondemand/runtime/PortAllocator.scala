package ai.starlake.quack.ondemand.runtime

import scala.collection.concurrent.TrieMap

final class PortAllocator(min: Int, max: Int):
  private val leased = TrieMap.empty[Int, Unit]

  def lease(): Option[Int] = synchronized {
    (min to max).find(p => !leased.contains(p)).map { p =>
      leased.put(p, ())
      p
    }
  }

  /** Hint to allocator that this port is in use (used on restart recovery). */
  def markLeased(port: Int): Unit =
    if port >= min && port <= max then leased.put(port, ())

  /** Lease `port` itself when it is in range and free; false when it is out of range or taken. */
  def tryLease(port: Int): Boolean = synchronized {
    if port < min || port > max || leased.contains(port) then false
    else
      leased.put(port, ())
      true
  }

  def release(port: Int): Unit = { leased.remove(port); () }
