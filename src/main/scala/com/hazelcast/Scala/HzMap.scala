package com.hazelcast.Scala

import java.util.Collections
import java.util.Comparator
import java.util.Map.Entry

import scala.collection.JavaConverters._
import scala.collection.mutable.{ Map => mMap }
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import com.hazelcast.Scala.dds.DDS
import com.hazelcast.Scala.dds.MapDDS
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.HazelcastInstanceAware
import com.hazelcast.core.IMap
import com.hazelcast.map.AbstractEntryProcessor
import com.hazelcast.query.PagingPredicate
import com.hazelcast.query.Predicate
import com.hazelcast.query.PredicateBuilder
import com.hazelcast.spi.AbstractDistributedObject

final class HzMap[K, V](protected val imap: IMap[K, V]) extends IMapDeltaUpdates[K, V] with MapEventSubscription {

  private[Scala] def getHZ: HazelcastInstance = imap match {
    case ado: AbstractDistributedObject[_] => ado.getNodeEngine.getHazelcastInstance
    case _ => getClientHzProxy(imap) getOrElse sys.error(s"Cannot get HazelcastInstance from ${imap.getClass}")
  }

  def async: AsyncMap[K, V] = new AsyncMap(imap)

  /**
    * NOTE: This method is generally slower than `get`
    * and also will not be part of `get` statistics.
    * It is meant for very large objects where only a
    * subset of the data is needed, thus limiting
    * unnecessary network traffic.
    */
  def getAs[R](key: K, map: V => R): Option[R] = async.getAs(key, map).await(DefaultFutureTimeout)

  def getAllAs[R](keys: Set[K], mf: V => R): mMap[K, R] = {
    if (keys.isEmpty) mMap.empty
    else {
      val ep = new AbstractEntryProcessor[K, V](false) {
        def process(entry: Entry[K, V]): Object = {
          entry.value match {
            case null => null
            case value => mf(value).asInstanceOf[Object]
          }
        }
      }
      imap.executeOnKeys(keys.asJava, ep).asScala.asInstanceOf[mMap[K, R]]
    }
  }

  def query[T](pred: Predicate[_, _])(mf: V => T): mMap[K, T] = {
    val ep = new HzMap.QueryEP(mf)
    imap.executeOnEntries(ep, pred).asScala.asInstanceOf[mMap[K, T]]
  }

  def foreach[E](env: HazelcastInstance => E, pred: Predicate[_, _] = null)(thunk: (E, Entry[K, V]) => Unit): Unit = {
    val ep = new HzMap.ForEachEP(env, thunk)
    pred match {
      case null => imap.executeOnEntries(ep)
      case pred => imap.executeOnEntries(ep, pred)
    }
  }

  private def updateValues(predicate: Option[Predicate[_, _]], update: V => V, returnValue: V => Object): mMap[K, V] = {
    val ep = new HzMap.ValueUpdaterEP(update, returnValue)
    val map = predicate match {
      case Some(predicate) => imap.executeOnEntries(ep, predicate)
      case None => imap.executeOnEntries(ep)
    }
    map.asScala.asInstanceOf[mMap[K, V]]
  }

  def update(predicate: Predicate[_, _] = null)(updateIfPresent: V => V): Unit = {
    updateValues(Option(predicate), updateIfPresent, _ => null)
  }
  def updateAndGet(predicate: Predicate[_, _])(updateIfPresent: V => V): mMap[K, V] = {
    updateValues(Option(predicate), updateIfPresent, _.asInstanceOf[Object])
  }

  def set(key: K, value: V, ttl: Duration) {
    if (ttl.isFinite && ttl.length > 0) {
      imap.set(key, value, ttl.length, ttl.unit)
    } else {
      imap.set(key, value)
    }
  }

  def put(key: K, value: V, ttl: Duration): Option[V] = {
    if (ttl.isFinite && ttl.length > 0) {
      Option(imap.put(key, value, ttl.length, ttl.unit))
    } else {
      Option(imap.put(key, value))
    }
  }
  def setTransient(key: K, value: V, ttl: Duration): Unit = {
    ttl match {
      case _: FiniteDuration => imap.putTransient(key, value, ttl.length, ttl.unit)
      case _ => imap.putTransient(key, value, 0, null)
    }
  }
  def putIfAbsent(key: K, value: V, ttl: Duration): Option[V] = {
    if (ttl.isFinite && ttl.length > 0) {
      Option(imap.putIfAbsent(key, value, ttl.length, ttl.unit))
    } else {
      Option(imap.putIfAbsent(key, value))
    }
  }

  def execute[R](filter: EntryFilter[K, V])(thunk: Entry[K, V] => R): mMap[K, R] = {
      def ep = new HzMap.ExecuteEP(thunk)
    val jMap: java.util.Map[K, Object] = filter match {
      case OnEntries(null) =>
        imap.executeOnEntries(ep)
      case OnEntries(predicate) =>
        imap.executeOnEntries(ep, predicate)
      case OnKey(key) =>
        val value = imap.executeOnKey(key, ep)
        Collections.singletonMap(key, value)
      case OnKeys(keys) =>
        if (keys.isEmpty) java.util.Collections.emptyMap()
        else imap.executeOnKeys(keys.asJava, ep)
      case OnValues(include) =>
        imap.executeOnEntries(ep, include)
    }
    jMap.asInstanceOf[java.util.Map[K, R]].asScala
  }

  type MSR = ListenerRegistration
  def onMapEvents(localOnly: Boolean, runOn: ExecutionContext)(pf: PartialFunction[MapEvent, Unit]): MSR = {
    val listener: com.hazelcast.map.listener.MapListener = new MapListener(pf, Option(runOn))
    val regId =
      if (localOnly) imap.addLocalEntryListener(listener)
      else imap.addEntryListener(listener, /* includeValue = */ false)
    new ListenerRegistration {
      def cancel(): Unit = imap.removeEntryListener(regId)
    }
  }
  def onPartitionLost(runOn: ExecutionContext)(listener: PartialFunction[PartitionLost, Unit]): MSR = {
    val regId = imap addPartitionLostListener asPartitionLostListener(listener, Option(runOn))
    new ListenerRegistration {
      def cancel = imap removePartitionLostListener regId
    }
  }

  def filter(pred: PredicateBuilder): DDS[Entry[K, V]] = new MapDDS(imap, pred)
  def filter(pred: Predicate[_, _]): DDS[Entry[K, V]] = new MapDDS(imap, pred)

  // TODO: Perhaps a macro could turn this into an IndexAwarePredicate?
  def filter(f: Entry[K, V] => Boolean): DDS[Entry[K, V]] = new MapDDS(imap, new EntryPredicate(f))

  def values[O: Ordering](range: Range, pred: Predicate[_, _] = null)(sortBy: Entry[K, V] => O, reverse: Boolean = false): Iterable[V] = {
    val pageSize = range.length
    val pageIdx = range.min / pageSize
    val dropValues = range.min % pageSize
    val comparator = new Comparator[Entry[K, V]] with Serializable {
      private[this] val ordering = implicitly[Ordering[O]] match {
        case comp if reverse => comp.reverse
        case comp => comp
      }
      def compare(a: Entry[K, V], b: Entry[K, V]): Int = ordering.compare(sortBy(a), sortBy(b))
    }.asInstanceOf[Comparator[Entry[_, _]]]
    val pp = new PagingPredicate(pred, comparator, pageSize)
    pp.setPage(pageIdx)
    val result = imap.values(pp).asScala
    if (dropValues == 0) result
    else result.drop(dropValues)
  }
  def entries[O: Ordering](range: Range, pred: Predicate[_, _] = null)(sortBy: Entry[K, V] => O, reverse: Boolean = false): Iterable[Entry[K, V]] = {
    val pageSize = range.length
    val pageIdx = range.min / pageSize
    val dropEntries = range.min % pageSize
    val comparator = new Comparator[Entry[K, V]] with Serializable {
      private[this] val ordering = implicitly[Ordering[O]] match {
        case comp if reverse => comp.reverse
        case comp => comp
      }
      def compare(a: Entry[K, V], b: Entry[K, V]): Int =
        ordering.compare(sortBy(a), sortBy(b))
    }.asInstanceOf[Comparator[Entry[_, _]]]
    val pp = new PagingPredicate(pred, comparator, pageSize)
    pp.setPage(pageIdx)
    val result = imap.entrySet(pp).iterator.asScala.toIterable
    if (dropEntries == 0) result
    else result.drop(dropEntries)
  }
  def keys[O: Ordering](range: Range, localOnly: Boolean = false, pred: Predicate[_, _] = null)(sortBy: Entry[K, V] => O, reverse: Boolean = false): Iterable[K] = {
    val pageSize = range.length
    val pageIdx = range.min / pageSize
    val dropEntries = range.min % pageSize
    val comparator = new Comparator[Entry[K, V]] with Serializable {
      private[this] val ordering = implicitly[Ordering[O]] match {
        case comp if reverse => comp.reverse
        case comp => comp
      }
      def compare(a: Entry[K, V], b: Entry[K, V]): Int =
        ordering.compare(sortBy(a), sortBy(b))
    }.asInstanceOf[Comparator[Entry[_, _]]]
    val pp = new PagingPredicate(pred, comparator, pageSize)
    pp.setPage(pageIdx)
    val result = (if (localOnly) imap.localKeySet(pp) else imap.keySet(pp)).iterator.asScala.toIterable
    if (dropEntries == 0) result
    else result.drop(dropEntries)
  }
}

private[Scala] object HzMap {
  final class QueryEP[V, T](_mf: V => T) extends AbstractEntryProcessor[Any, V](false) {
    def mf = _mf
    def process(entry: Entry[Any, V]): Object = _mf(entry.value).asInstanceOf[Object]
  }
  final class ForEachEP[K, V, E](val getEnv: HazelcastInstance => E, _thunk: (E, Entry[K, V]) => Unit)
      extends AbstractEntryProcessor[K, V](false)
      with HazelcastInstanceAware {
    def thunk = _thunk
    @transient
    private[this] var env: E = _
    def setHazelcastInstance(hz: HazelcastInstance) = env = getEnv(hz)
    def process(entry: Entry[K, V]): Object = {
      _thunk(env, entry)
      null
    }
  }
  final class ValueUpdaterEP[V](_update: V => V, _returnValue: V => Object) extends AbstractEntryProcessor[Any, V](true) {
    def update = _update
    def returnValue = _returnValue
    def process(entry: Entry[Any, V]): Object = {
      entry.value = _update(entry.value)
      _returnValue(entry.value)
    }
  }
  final class ExecuteEP[K, V, R](_thunk: Entry[K, V] => R) extends AbstractEntryProcessor[K, V](true) {
    def thunk = _thunk
    def process(entry: Entry[K, V]): Object = _thunk(entry) match {
      case null | _: Unit => null
      case value => value.asInstanceOf[Object]
    }
  }
}
