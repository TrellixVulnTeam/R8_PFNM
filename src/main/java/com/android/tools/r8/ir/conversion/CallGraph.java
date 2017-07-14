// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.Invoke.Type;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Call graph representation.
 * <p>
 * Each node in the graph contain the methods called and the calling methods. For virtual and
 * interface calls all potential calls from subtypes are recorded.
 * <p>
 * Only methods in the program - not library methods - are represented.
 * <p>
 * The directional edges are represented as sets of nodes in each node (called methods and callees).
 * <p>
 * A call from method <code>a</code> to method <code>b</code> is only present once no matter how
 * many calls of <code>a</code> there are in <code>a</code>.
 * <p>
 * Recursive calls are not present.
 */
public class CallGraph {

  private class Node {

    public final DexEncodedMethod method;
    private int invokeCount = 0;
    private boolean isSelfRecursive = false;

    // Outgoing calls from this method.
    public final Set<Node> callees = new LinkedHashSet<>();

    // Incoming calls to this method.
    public final Set<Node> callers = new LinkedHashSet<>();

    private Node(DexEncodedMethod method) {
      this.method = method;
    }

    public boolean isBridge() {
      return method.accessFlags.isBridge();
    }

    private void addCallee(Node method) {
      callees.add(method);
    }

    private void addCaller(Node method) {
      callers.add(method);
    }

    boolean isSelfRecursive() {
      return isSelfRecursive;
    }

    boolean isLeaf() {
      return callees.isEmpty();
    }

    int callDegree() {
      return callees.size();
    }

    @Override
    public int hashCode() {
      return method.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("MethodNode for: ");
      builder.append(method.qualifiedName());
      builder.append(" (");
      builder.append(callees.size());
      builder.append(" callees, ");
      builder.append(callers.size());
      builder.append(" callers");
      if (isBridge()) {
        builder.append(", bridge");
      }
      if (isSelfRecursive()) {
        builder.append(", recursive");
      }
      builder.append(", invoke count " + invokeCount);
      builder.append(").\n");
      if (callees.size() > 0) {
        builder.append("Callees:\n");
        for (Node call : callees) {
          builder.append("  ");
          builder.append(call.method.qualifiedName());
          builder.append("\n");
        }
      }
      if (callers.size() > 0) {
        builder.append("Callers:\n");
        for (Node caller : callers) {
          builder.append("  ");
          builder.append(caller.method.qualifiedName());
          builder.append("\n");
        }
      }
      return builder.toString();
    }
  }

  public class Leaves {

    private final List<DexEncodedMethod> leaves;
    private final boolean brokeCycles;
    private final Map<DexEncodedMethod, Set<DexEncodedMethod>> cycleBreakingCalls;

    private Leaves(List<DexEncodedMethod> leaves, boolean brokeCycles,
        Map<DexEncodedMethod, Set<DexEncodedMethod>> cycleBreakingCalls) {
      this.leaves = leaves;
      this.brokeCycles = brokeCycles;
      this.cycleBreakingCalls = cycleBreakingCalls;
      assert brokeCycles == (cycleBreakingCalls.size() != 0);
    }

    public int size() {
      return leaves.size();
    }

    public List<DexEncodedMethod> getLeaves() {
      return leaves;
    }

    public boolean hasBrokeCycles() {
      return brokeCycles;
    }

    /**
     * Calls that were broken to produce the leaves.
     *
     * If {@link Leaves#breakCycles()} return <code>true</code> this provides the calls for each
     * leaf that were broken.
     *
     * NOTE: The broken calls are not confined to the set of leaves.
     */
    public Map<DexEncodedMethod, Set<DexEncodedMethod>> getCycleBreakingCalls() {
      return cycleBreakingCalls;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("Leaves: ");
      builder.append(leaves.size());
      builder.append("\n");
      builder.append(brokeCycles ? "Call cycles broken" : "No call cycles broken");
      return builder.toString();
    }
  }

  private final Map<DexEncodedMethod, Node> nodes = new LinkedHashMap<>();
  private final Map<DexEncodedMethod, Set<DexEncodedMethod>> breakers = new HashMap<>();

  private List<Node> leaves = null;
  private Set<DexEncodedMethod> singleCallSite = Sets.newIdentityHashSet();
  private Set<DexEncodedMethod> doubleCallSite = Sets.newIdentityHashSet();

  public static CallGraph build(DexApplication application, AppInfoWithSubtyping appInfo,
      GraphLense graphLense) {

    CallGraph graph = new CallGraph();
    for (DexClass clazz : application.classes()) {
      clazz.forEachMethod( method -> {
        Node node = graph.ensureMethodNode(method);
        InvokeExtractor extractor = new InvokeExtractor(appInfo, graphLense, node, graph);
        method.registerReachableDefinitions(extractor);
      });
    }

    assert allMethodsExists(application, graph);
    graph.fillCallSiteSets(appInfo);
    graph.fillInitialLeaves();
    return graph;
  }

  /**
   * Check if the <code>method</code> is guaranteed to only have a single call site.
   * <p>
   * For pinned methods (methods kept through Proguard keep rules) this will always answer
   * <code>false</code>.
   */
  public boolean hasSingleCallSite(DexEncodedMethod method) {
    return singleCallSite.contains(method);
  }

  public boolean hasDoubleCallSite(DexEncodedMethod method) {
    return doubleCallSite.contains(method);
  }

  private void fillCallSiteSets(AppInfoWithSubtyping appInfo) {
    assert singleCallSite.isEmpty();
    AppInfoWithLiveness liveAppInfo = appInfo.withLiveness();
    if (liveAppInfo == null) {
      return;
    }
    for (Node value : nodes.values()) {
      // For non-pinned methods we know the exact number of call sites.
      if (!appInfo.withLiveness().pinnedItems.contains(value.method)) {
        if (value.invokeCount == 1) {
          singleCallSite.add(value.method);
        } else if (value.invokeCount == 2) {
          doubleCallSite.add(value.method);
        }
      }
    }
  }

  private void fillInitialLeaves() {
    assert leaves == null;
    leaves = new ArrayList<>();
    for (Node node : nodes.values()) {
      if (node.isLeaf()) {
        leaves.add(node);
      }
    }
  }

  private static boolean allMethodsExists(DexApplication application, CallGraph graph) {
    for (DexProgramClass clazz : application.classes()) {
      clazz.forEachMethod( method -> { assert graph.nodes.get(method) != null; });
    }
    return true;
  }

  /**
   * Remove all leaves (nodes with an call (outgoing) degree of 0).
   * <p>
   *
   * @return List of {@link DexEncodedMethod} of the leaves removed.
   */
  private List<DexEncodedMethod> removeLeaves() {
    List<DexEncodedMethod> result = new ArrayList<>();
    List<Node> newLeaves = new ArrayList<>();
    for (Node leaf : leaves) {
      assert nodes.containsKey(leaf.method) && nodes.get(leaf.method).callees.isEmpty();
      remove(leaf, newLeaves);
      result.add(leaf.method);
    }
    leaves = newLeaves;
    return result;
  }

  /**
   * Pick the next set of leaves (nodes with an call (outgoing) degree of 0) if any.
   * <p>
   * If the graph has no leaves then some cycles in the graph will be broken to create a set of
   * leaves. See {@link #breakCycles} on how cycles are broken. This ensures that at least one
   * leave is returned if the graph is not empty.
   * <p>
   *
   * @return object with the leaves as a List of {@link DexEncodedMethod} and <code>boolean</code>
   * indication of whether cycles were broken to produce leaves. <code>null</code> if the graph is
   * empty.
   */
  public Leaves pickLeaves() {
    boolean cyclesBroken = false;
    Map<DexEncodedMethod, Set<DexEncodedMethod>> brokenCalls = Collections.emptyMap();
    if (isEmpty()) {
      return null;
    }
    List<DexEncodedMethod> leaves = removeLeaves();
    if (leaves.size() == 0) {
      // The graph had no more leaves, so break cycles to construct leaves.
      brokenCalls = breakCycles();
      cyclesBroken = true;
      leaves = removeLeaves();
    }
    assert leaves.size() > 0;
    for (DexEncodedMethod leaf : leaves) {
      assert !leaf.isProcessed();
    }
    return new Leaves(leaves, cyclesBroken, brokenCalls);
  }

  /**
   * Break some cycles in the graph by removing edges.
   * <p>
   * This will find the lowest call (outgoing) degree in the graph. Then go through all nodes with
   * that call (outgoing) degree, and remove all call (outgoing) edges from nodes with that call
   * (outgoing) degree.
   * <p>
   * It will avoid removing edges from bridge-methods if possible.
   * <p>
   * Returns the calls that were broken.
   */
  private Map<DexEncodedMethod, Set<DexEncodedMethod>> breakCycles() {
    Map<DexEncodedMethod, Set<DexEncodedMethod>> brokenCalls = new IdentityHashMap<>();
    // Break non bridges with degree 1.
    int minDegree = nodes.size();
    for (Node node : nodes.values()) {
      // Break cycles and add all leaves created in the process.
      if (!node.isBridge() && node.callDegree() <= 1) {
        assert node.callDegree() == 1;
        Set<DexEncodedMethod> calls = removeAllCalls(node);
        leaves.add(node);
        brokenCalls.put(node.method, calls);
      } else {
        minDegree = Integer.min(minDegree, node.callDegree());
      }
    }

    // Return if new leaves were created.
    if (leaves.size() > 0) {
      return brokenCalls;
    }

    // Break methods with the found minimum degree and add all leaves created in the process.
    for (Node node : nodes.values()) {
      if (node.callDegree() <= minDegree) {
        assert node.callDegree() == minDegree;
        Set<DexEncodedMethod> calls = removeAllCalls(node);
        leaves.add(node);
        brokenCalls.put(node.method, calls);
      }
    }
    assert leaves.size() > 0;
    return brokenCalls;
  }

  synchronized private Node ensureMethodNode(DexEncodedMethod method) {
    return nodes.computeIfAbsent(method, k -> new Node(method));
  }

  synchronized private void addCall(Node caller, Node callee) {
    assert caller != null;
    assert callee != null;
    if (caller != callee) {
      caller.addCallee(callee);
      callee.addCaller(caller);
    } else {
      caller.isSelfRecursive = true;
    }
    callee.invokeCount++;
  }

  private Set<DexEncodedMethod> removeAllCalls(Node node) {
    Set<DexEncodedMethod> calls = Sets.newIdentityHashSet();
    for (Node call : node.callees) {
      calls.add(call.method);
      call.callers.remove(node);
    }
    node.callees.clear();
    return calls;
  }

  private void remove(Node node, List<Node> leaves) {
    assert node != null;
    for (Node caller : node.callers) {
      boolean removed = caller.callees.remove(node);
      if (caller.isLeaf()) {
        leaves.add(caller);
      }
      assert removed;
    }
    nodes.remove(node.method);
  }

  public boolean isEmpty() {
    return nodes.size() == 0;
  }

  public void dump() {
    nodes.forEach((m, n) -> System.out.println(n + "\n"));
  }

  private static class InvokeExtractor extends UseRegistry {

    AppInfoWithSubtyping appInfo;
    GraphLense graphLense;
    Node caller;
    CallGraph graph;

    InvokeExtractor(AppInfoWithSubtyping appInfo, GraphLense graphLense, Node caller,
        CallGraph graph) {
      this.appInfo = appInfo;
      this.graphLense = graphLense;
      this.caller = caller;
      this.graph = graph;
    }

    private void processInvoke(DexEncodedMethod source, Invoke.Type type, DexMethod method) {
      method = graphLense.lookupMethod(method, source);
      DexEncodedMethod definition = appInfo.lookup(type, method);
      if (definition != null) {
        assert !source.accessFlags.isBridge() || definition != caller.method;
        DexType definitionHolder = definition.method.getHolder();
        assert definitionHolder.isClassType();
        if (!appInfo.definitionFor(definitionHolder).isLibraryClass()) {
          Node callee = graph.ensureMethodNode(definition);
          graph.addCall(caller, callee);
          // For virtual and interface calls add all potential targets that could be called.
          if (type == Type.VIRTUAL || type == Type.INTERFACE) {
            Set<DexEncodedMethod> possibleTargets;
            if (definitionHolder.isInterface()) {
              possibleTargets = appInfo.lookupInterfaceTargets(definition.method);
            } else {
              possibleTargets = appInfo.lookupVirtualTargets(definition.method);
            }
            for (DexEncodedMethod possibleTarget : possibleTargets) {
              if (possibleTarget != definition) {
                DexClass possibleTargetClass =
                    appInfo.definitionFor(possibleTarget.method.getHolder());
                if (possibleTargetClass != null && !possibleTargetClass.isLibraryClass()) {
                  callee = graph.ensureMethodNode(possibleTarget);
                  graph.addCall(caller, callee);
                }
              }
            }
          }
        }
      }
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      processInvoke(caller.method, Type.VIRTUAL, method);
      return false;
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      processInvoke(caller.method, Type.DIRECT, method);
      return false;
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      processInvoke(caller.method, Type.STATIC, method);
      return false;
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      processInvoke(caller.method, Type.INTERFACE, method);
      return false;
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      processInvoke(caller.method, Type.SUPER, method);
      return false;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      return false;
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      return false;
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      return false;
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      return false;
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      return false;
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      return false;
    }
  }
}
