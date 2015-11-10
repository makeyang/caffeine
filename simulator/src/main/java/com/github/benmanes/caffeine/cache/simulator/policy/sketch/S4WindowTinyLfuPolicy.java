/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import static com.google.common.base.Preconditions.checkState;

import java.util.Arrays;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the eden space implements LRU and the main space implements
 * S4LRU.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class S4WindowTinyLfuPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final Node[] headMainQ;
  private final int maximumSize;
  private final Node headEden;
  private final int maxMain;
  private final int maxEden;

  private int levels;
  private int sizeEden;
  private int[] sizeMainQ;

  public S4WindowTinyLfuPolicy(String name, Config config) {
    S4WindowTinyLfuSettings settings = new S4WindowTinyLfuSettings(config);
    this.maximumSize = settings.maximumSize();
    this.maxMain = (int) (maximumSize * settings.percentMain());
    this.maxEden = maximumSize - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(config);
    this.headEden = Node.sentinel(-1);
    this.levels = settings.levels();
    this.sizeMainQ = new int[levels];
    this.headMainQ = new Node[levels];
    Arrays.setAll(headMainQ, Node::sentinel);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else if (node.status == Status.EDEN) {
      onEdenHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.MAIN) {
      onMainHit(node);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    Node node = new Node(key, Status.EDEN);
    node.appendToTail(headEden);
    data.put(key, node);
    sizeEden++;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onEdenHit(Node node) {
    node.moveToTail(headEden);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onMainHit(Node node) {
    node.remove();
    sizeMainQ[node.level]--;
    if (node.level < (levels - 1)) {
      node.level++;
    }

    Node head = headMainQ[node.level];
    node.appendToTail(head);
    sizeMainQ[node.level]++;

    adjust();
  }

  private void adjust() {
    int maxPerLevel = maxMain / levels;
    for (int i = levels - 1; i > 0; i--) {
      if (sizeMainQ[i] > maxPerLevel) {
        Node demote = headMainQ[i].next;
        demote.remove();
        sizeMainQ[i]--;

        demote.level = i - 1;
        sizeMainQ[demote.level]++;
        demote.appendToTail(headMainQ[demote.level]);
      }
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict() {
    if (sizeEden <= maxEden) {
      return;
    }

    Node candidate = headEden.next;
    candidate.remove();
    sizeEden--;

    candidate.appendToTail(headMainQ[0]);
    candidate.status = Status.MAIN;
    sizeMainQ[0]++;

    if (data.size() > maximumSize) {
      Node victim = headMainQ[0].next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();
      sizeMainQ[0]--;

      policyStats.recordEviction();
    }
  }

  @Override
  public void finished() {
    for (int i = 0; i < levels; i++) {
      int level = i;
      int count = (int) data.values().stream()
          .filter(node -> node.status == Status.MAIN)
          .filter(node -> node.level == level)
          .count();
      checkState(count == sizeMainQ[i]);
    }
    checkState(data.values().stream().filter(n -> n.status == Status.EDEN).count() == sizeEden);
    checkState(data.size() <= maxEden + maxMain);
  }

  enum Status {
    EDEN, MAIN
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    int recencyMove;
    Status status;
    Node prev;
    Node next;
    int level;

    /** Creates a new, unlinked node. */
    public Node(long key, Status status) {
      this.status = status;
      this.key = key;
    }

    /** Creates a new sentinel node. */
    static Node sentinel(int level) {
      Node node = new Node(Long.MIN_VALUE, null);
      node.level = level;
      node.prev = node;
      node.next = node;
      return node;
    }

    public boolean isInQueue() {
      return next != null;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("level", level)
          .toString();
    }
  }

  static final class S4WindowTinyLfuSettings extends BasicSettings {
    public S4WindowTinyLfuSettings(Config config) {
      super(config);
    }
    public int levels() {
      return config().getInt("s4-window-tiny-lfu.levels");
    }
    public double percentMain() {
      return config().getDouble("s4-window-tiny-lfu.percent-main");
    }
  }
}
