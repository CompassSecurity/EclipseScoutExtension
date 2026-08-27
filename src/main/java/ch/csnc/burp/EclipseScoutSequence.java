package ch.csnc.burp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import burp.api.montoya.http.message.requests.HttpRequest;

public class EclipseScoutSequence {

  /**
   * Resolves an original-capture id to its replay-run counterpart by matching classId - a
   * widget's session-independent structural identity - rather than position. Shared by
   * adapter ids (target) and tree node ids (nodeId), which each have their own classId space.
   *
   * <p>Several ids can share one classId (e.g. one generated Menu item per business-type code,
   * all built from the same prototype) - a plain classId-to-id map would then wrongly resolve
   * every one of them to whichever replay id happened to be seen first. Instead this tracks,
   * per classId, the ordered list of ids that had it on each side, and matches by position
   * within that list: the Nth original occurrence resolves to the Nth replay occurrence. This
   * assumes Scout produces same-classId siblings in the same relative order each run - true as
   * long as the data driving them (e.g. a CodeType list) hasn't changed between capture and
   * replay.
   */
  private static final class IdentityResolver {

    private final Map<String, String> originalIdToClassId = new HashMap<>();
    private final Map<String, List<String>> originalIdsByClassId = new HashMap<>();
    private final Map<String, List<String>> replayIdsByClassId = new HashMap<>();

    /**
     * Registers ids seen in an originally captured response, so later {@link #resolve} calls
     * can map each one to its replay-run counterpart by classId and occurrence order.
     */
    void learnOriginal(Map<String, String> idToClassId) {
      idToClassId.forEach((id, classId) -> {
        this.originalIdToClassId.put(id, classId);
        this.originalIdsByClassId.computeIfAbsent(classId, key -> new ArrayList<>()).add(id);
      });
    }

    /**
     * Registers ids seen in a response produced during this replay run, extending each
     * classId's list of replay-side occurrences that {@link #resolve} matches against.
     */
    void learnReplay(Map<String, String> idToClassId) {
      idToClassId.forEach(
          (id, classId) -> this.replayIdsByClassId.computeIfAbsent(classId, key -> new ArrayList<>()).add(id));
    }

    /**
     * The replay-run id that corresponds to {@code originalId}, found by locating its classId
     * and matching by occurrence order within that classId, or {@code null} if {@code originalId}
     * is unknown or its classId hasn't been observed yet on the replay side.
     */
    String resolve(String originalId) {
      var classId = this.originalIdToClassId.get(originalId);
      if (classId == null) {
        return null;
      }
      var replaySiblings = this.replayIdsByClassId.get(classId);
      if (replaySiblings == null) {
        return null;
      }
      var index = this.originalIdsByClassId.get(classId).indexOf(originalId);
      return index >= 0 && index < replaySiblings.size() ? replaySiblings.get(index) : null;
    }
  }

  private int position = 0;

  private List<EclipseScoutExchange> exchanges;

  private final List<EclipseScoutVariable> variableDefinitions;

  /**
   * Current value of each variable, as most recently extracted from a response during this
   * replay run - global across the whole sequence, not per-exchange. Starts empty each run.
   */
  private final Map<String, String> variableValues = new HashMap<>();

  private final IdentityResolver adapterResolver = new IdentityResolver();
  private final IdentityResolver nodeResolver = new IdentityResolver();

  /** A table row's original id, mapped to the id of the tree node it mirrors, if any. */
  private final Map<String, String> originalRowIdToNodeId = new HashMap<>();

  /**
   * A tree node's replay-run id, mapped to whichever row currently mirrors it - the reverse of
   * {@link #originalRowIdToNodeId}, but observed live during replay. Unlike classId, a given
   * node is expected to be mirrored by exactly one row, so first occurrence wins without the
   * ambiguity {@link IdentityResolver} has to account for.
   */
  private final Map<String, String> replayNodeIdToRowId = new HashMap<>();

  /**
   * Starts a fresh replay run over independent clones of {@code exchanges} (so replaying never
   * mutates the caller's own captured rows) and primes both {@link IdentityResolver}s and
   * {@link #originalRowIdToNodeId} from every exchange's originally captured response, ahead of
   * seeing any replay-side responses.
   */
  public EclipseScoutSequence(List<EclipseScoutExchange> exchanges, List<EclipseScoutVariable> variableDefinitions) {
    this.exchanges = exchanges.stream().map(EclipseScoutExchange::clone).toList();
    this.variableDefinitions = variableDefinitions;
    for (var exchange : this.exchanges) {
      this.adapterResolver.learnOriginal(exchange.responseAdapterClassIds());
      this.nodeResolver.learnOriginal(exchange.responseNodeClassIds());
      this.originalRowIdToNodeId.putAll(exchange.responseRowNodeIds());
    }
  }

  /** Whether {@link #next()} has another exchange left to return in this replay run. */
  public boolean hasNext() {
    return this.position < this.exchanges.size();
  }

  /**
   * Returns the next exchange in display order, propagating the UI session id from the
   * previous exchange, renumbering its body's "#" sequence number to match its position,
   * remapping "target"/"nodeId"/"rowId" ids in its request via classId-based identity
   * matching, and substituting any {@code $VAR:name$} placeholders in its request with the
   * variables' current values: an id captured against the original session is resolved to
   * whatever id that same structural widget/node (same classId) - or, for rows, the node it
   * mirrors - was assigned during THIS replay run. This keeps the sequence pointed at the right
   * widget even when replay assigns different numeric ids - e.g. because an earlier step in the
   * sequence added something that shifted every id created after it.
   *
   * <p>Relies on the previous exchange already having been sent (its response set) before this
   * is called - true both for the plain send loop ({@code next().send()} repeated) and for
   * {@link #advanceTo(int)}, and required already for UI session id propagation above. That
   * same response is where each variable's current value is (re-)extracted from, before it's
   * substituted into the exchange this call returns - so a value captured from one step is
   * available to the very next one.
   */
  public EclipseScoutExchange next() {
    return this.next(null);
  }

  /**
   * Like {@link #next()}, but if {@code liveOverride} is non-null, the exchange's captured
   * request is replaced with it before the pipeline runs - so a request edited live in
   * Repeater's or Intruder's pane still gets uiSessionId propagation, sequence renumbering, id
   * remapping and variable substitution applied on top of the edit, rather than those being
   * computed against - and then discarded in favor of losing - the original capture.
   */
  EclipseScoutExchange next(HttpRequest liveOverride) {
    var exchange = this.exchanges.get(this.position);
    if (liveOverride != null) {
      exchange.overrideRequest(liveOverride);
    }
    if (this.position > 0) {
      var previous = this.exchanges.get(this.position - 1);
      exchange.setUiSessionId(previous.getUiSessionId());
      this.adapterResolver.learnReplay(previous.responseAdapterClassIds());
      this.nodeResolver.learnReplay(previous.responseNodeClassIds());
      previous.responseRowNodeIds()
          .forEach((rowId, nodeId) -> this.replayNodeIdToRowId.putIfAbsent(nodeId, rowId));
      this.variableValues.putAll(previous.extractVariables(this.variableDefinitions));
    }
    exchange.renumberSequenceNr(this.position);
    exchange.remapIds(this.adapterResolver::resolve, this.nodeResolver::resolve, this::resolveRowId);
    exchange.substituteVariables(this.variableValues);
    this.position++;
    return exchange;
  }

  /**
   * The replay-run row id that mirrors the same node as {@code originalRowId} did originally,
   * found by resolving the node it mirrored to its replay-run id and looking up which row is
   * currently observed mirroring that node - or {@code null} if any step of that chain is
   * unresolvable (the row has no mirrored node, its node's classId hasn't been matched yet, or
   * no row has been observed mirroring the resolved node).
   */
  private String resolveRowId(String originalRowId) {
    var originalNodeId = this.originalRowIdToNodeId.get(originalRowId);
    if (originalNodeId == null) {
      return null;
    }
    var replayNodeId = this.nodeResolver.resolve(originalNodeId);
    if (replayNodeId == null) {
      return null;
    }
    return this.replayNodeIdToRowId.get(replayNodeId);
  }

  /**
   * Finds the position of the exchange with the given static {@code nr} in display order,
   * or -1 if no such exchange exists.
   */
  public int indexOfNr(int nr) {
    for (int i = 0; i < this.exchanges.size(); i++) {
      var exchangeNr = this.exchanges.get(i).nr();
      if (exchangeNr != null && exchangeNr == nr) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Sends every exchange strictly before {@code index} (so their responses/session state
   * are established), then returns the exchange at {@code index} without sending it -
   * that one is expected to be sent by the caller instead.
   */
  public EclipseScoutExchange advanceTo(int index) {
    return this.advanceTo(index, null);
  }

  /**
   * Like {@link #advanceTo(int)}, but the exchange at {@code index} is built from
   * {@code liveOverride} - a request just edited live in Repeater's or Intruder's pane - instead
   * of from its own captured request, if {@code liveOverride} is non-null. See
   * {@link #next(HttpRequest)}.
   */
  public EclipseScoutExchange advanceTo(int index, HttpRequest liveOverride) {
    while (this.position < index) {
      this.next().send();
    }
    return this.next(liveOverride);
  }

  /**
   * Returns the exchange with the given static {@code nr}, or {@code null} if no such
   * exchange exists. Its response reflects this replay run once the exchange has been
   * sent (via {@link #next()}/{@link #advanceTo(int)} or externally).
   */
  public EclipseScoutExchange exchangeWithNr(int nr) {
    var index = indexOfNr(nr);
    return index >= 0 ? this.exchanges.get(index) : null;
  }

}
