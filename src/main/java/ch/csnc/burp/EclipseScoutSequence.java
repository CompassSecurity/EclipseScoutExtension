package ch.csnc.burp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

  /**
   * Resolves an id that carries no reliable classId of its own for {@link IdentityResolver} to
   * match on, from the original capture to its replay-run counterpart, by matching its owning
   * parent id and its position among that parent's children: the Nth child of parent X
   * originally resolves to the Nth child of parent X during replay. Used for two unrelated kinds
   * of child: a plain table row - one that isn't mirroring a tree node, so
   * {@link #originalRowIdToNodeId} has nothing for it (the Nth row of table X, whether it
   * started there from the table's own "adapterData" creation payload or was added later via a
   * "rowsInserted" event - see {@link EclipseScoutExchange#responseTableRowIds()}) - and a
   * dynamically generated field of a composite widget, whose classId may not be stable across
   * runs unlike a field instantiated from a fixed, compiled widget class (the Nth entry of an
   * adapter's "fields" array - see {@link EclipseScoutExchange#responseAdapterFieldIds()}). For
   * rows, this is the last resort {@link #resolveRowId} falls back to; for adapter ids in
   * general (both "target" and "field"), it's what {@link #resolveAdapterId} falls back to once
   * classId matching has failed - recursively, since a dynamically generated adapter's parent
   * may itself be dynamically generated.
   *
   * <p>The parent itself is identified by its <em>original</em>-capture id on both sides:
   * {@link #learnReplay} records replay-side children under whatever parent id the replay
   * response actually used, and {@link #resolve} translates the original parent id to that via
   * {@code parentResolver} (the same {@link #adapterResolver} that already resolves "target"
   * ids) rather than assuming the parent keeps the same id run to run.
   */
  private static final class PositionalIdResolver {

    private final Map<String, String> originalIdToParent = new HashMap<>();
    private final Map<String, List<String>> originalIdsByParent = new HashMap<>();
    private final Map<String, List<String>> replayIdsByParent = new HashMap<>();

    /** Registers child ids seen in an originally captured response, keyed by their owning parent's id. */
    void learnOriginal(Map<String, List<String>> idsByParent) {
      idsByParent.forEach((parent, ids) -> ids.forEach(id -> {
        this.originalIdToParent.put(id, parent);
        this.originalIdsByParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(id);
      }));
    }

    /** Registers child ids seen in a response produced during this replay run, under their own parent's id. */
    void learnReplay(Map<String, List<String>> idsByParent) {
      idsByParent.forEach((parent, ids) ->
          this.replayIdsByParent.computeIfAbsent(parent, key -> new ArrayList<>()).addAll(ids));
    }

    /**
     * The replay-run id that corresponds to {@code originalId}, found by resolving its owning
     * parent's id via {@code parentResolver} (falling back to the original parent id unchanged
     * if that resolves to nothing) and matching by position within that parent's children, or
     * {@code null} if {@code originalId} is unknown or its parent hasn't shown that many
     * children yet on the replay side.
     */
    String resolve(String originalId, Function<String, String> parentResolver) {
      var originalParent = this.originalIdToParent.get(originalId);
      if (originalParent == null) {
        return null;
      }
      var replayParent = parentResolver.apply(originalParent);
      var replaySiblings = this.replayIdsByParent.get(replayParent != null ? replayParent : originalParent);
      if (replaySiblings == null) {
        return null;
      }
      var index = this.originalIdsByParent.get(originalParent).indexOf(originalId);
      return index >= 0 && index < replaySiblings.size() ? replaySiblings.get(index) : null;
    }
  }

  /**
   * Notified, in send order, each time {@link #sendNext()} finishes sending one exchange -
   * {@code sequence} is {@code this}, so a listener can read {@link #exchanges()} for a full
   * snapshot of the run so far, not just the exchange that just completed.
   */
  public interface StepListener {
    void onStep(EclipseScoutSequence sequence, EclipseScoutExchange exchange, int index);
  }

  private int position = 0;

  private List<EclipseScoutExchange> exchanges;

  private final List<EclipseScoutVariable> variableDefinitions;

  private StepListener stepListener;

  /**
   * Current value of each variable, as most recently extracted from a response during this
   * replay run - global across the whole sequence, not per-exchange. Starts empty each run.
   */
  private final Map<String, String> variableValues = new HashMap<>();

  private final IdentityResolver adapterResolver = new IdentityResolver();
  private final IdentityResolver nodeResolver = new IdentityResolver();
  private final PositionalIdResolver tableRowResolver = new PositionalIdResolver();
  private final PositionalIdResolver fieldResolver = new PositionalIdResolver();

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
   * mutates the caller's own captured rows) and primes both {@link IdentityResolver}s,
   * {@link #originalRowIdToNodeId}, {@link #tableRowResolver} and {@link #fieldResolver} from
   * every exchange's originally captured response, ahead of seeing any replay-side responses.
   */
  public EclipseScoutSequence(List<EclipseScoutExchange> exchanges, List<EclipseScoutVariable> variableDefinitions) {
    this.exchanges = exchanges.stream().map(EclipseScoutExchange::clone).toList();
    this.variableDefinitions = variableDefinitions;
    for (var exchange : this.exchanges) {
      this.adapterResolver.learnOriginal(exchange.responseAdapterClassIds());
      this.nodeResolver.learnOriginal(exchange.responseNodeClassIds());
      this.originalRowIdToNodeId.putAll(exchange.responseRowNodeIds());
      this.tableRowResolver.learnOriginal(exchange.responseTableRowIds());
      this.fieldResolver.learnOriginal(exchange.responseAdapterFieldIds());
    }
  }

  /** Whether {@link #next()} has another exchange left to return in this replay run. */
  public boolean hasNext() {
    return this.position < this.exchanges.size();
  }

  /**
   * Registers {@code listener} to be notified after each exchange {@link #sendNext()} sends,
   * e.g. to drive a live view of a run in progress. Replaces any previously set listener;
   * {@code null} stops notifying.
   */
  public void setStepListener(StepListener listener) {
    this.stepListener = listener;
  }

  /**
   * Every exchange in this run, in display order - the live backing list, not a copy, so its
   * clones reflect whatever has been sent/received so far as the run progresses.
   */
  public List<EclipseScoutExchange> exchanges() {
    return this.exchanges;
  }

  /**
   * Combines {@link #next()} and {@link EclipseScoutExchange#send()}, notifying the
   * {@link #setStepListener registered step listener}, if any, once the response is in.
   */
  public EclipseScoutExchange sendNext() {
    var exchange = this.next();
    exchange.send();
    if (this.stepListener != null) {
      this.stepListener.onStep(this, exchange, this.position - 1);
    }
    return exchange;
  }

  /**
   * Returns the next exchange in display order, propagating the UI session id from the
   * previous exchange, renumbering its body's "#" sequence number to match its position,
   * remapping "target"/"field"/"nodeId"/"rowId" ids in its request via classId-based identity
   * matching, and substituting any {@code $VAR:name$} placeholders in its request with the
   * variables' current values: an id captured against the original session is resolved to
   * whatever id that same structural widget/node (same classId) - or, for rows and dynamically
   * generated fields whose classId isn't stable across runs, the position among their owning
   * parent's children - was assigned during THIS replay run. This keeps the sequence pointed at
   * the right widget even when replay assigns different numeric ids - e.g. because an earlier
   * step in the sequence added something that shifted every id created after it.
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
      this.tableRowResolver.learnReplay(previous.responseTableRowIds());
      this.fieldResolver.learnReplay(previous.responseAdapterFieldIds());
      this.variableValues.putAll(previous.extractVariables(this.variableDefinitions));
    }
    exchange.renumberSequenceNr(this.position);
    exchange.remapIds(this::resolveAdapterId, this::resolveAdapterId, this.nodeResolver::resolve, this::resolveRowId);
    exchange.substituteVariables(this.variableValues);
    this.position++;
    return exchange;
  }

  /**
   * The replay-run row id corresponding to {@code originalRowId}, tried first as a "detail
   * table" row mirroring a tree node (see {@link #resolveMirroredRowId}), then, if that finds
   * nothing, as a plain table row via {@link #tableRowResolver}. {@code null} if neither
   * strategy resolves it.
   */
  private String resolveRowId(String originalRowId) {
    var viaMirroredNode = resolveMirroredRowId(originalRowId);
    if (viaMirroredNode != null) {
      return viaMirroredNode;
    }
    return this.tableRowResolver.resolve(originalRowId, this::resolveAdapterId);
  }

  /**
   * The replay-run id corresponding to {@code originalId} - an adapter id referenced via either
   * "target" or "field" - tried first via {@link #adapterResolver} (classId-based identity,
   * right for an adapter instantiated from a fixed, compiled widget class), then, if that finds
   * nothing, via {@link #fieldResolver}: its owning adapter (itself resolved the same recursive
   * way, since it may be just as dynamically generated) and its position among that adapter's
   * "fields" - the fallback a dynamically generated adapter, whose classId isn't stable across
   * runs, needs instead. A whole subtree of dynamically generated adapters (e.g. a custom
   * "field per business-configured item" widget whose every descendant is generated fresh each
   * session) resolves this way, one level of nesting at a time. {@code null} if neither strategy
   * resolves it.
   */
  private String resolveAdapterId(String originalId) {
    var viaClassId = this.adapterResolver.resolve(originalId);
    if (viaClassId != null) {
      return viaClassId;
    }
    return this.fieldResolver.resolve(originalId, this::resolveAdapterId);
  }

  /**
   * The replay-run row id that mirrors the same node as {@code originalRowId} did originally,
   * found by resolving the node it mirrored to its replay-run id and looking up which row is
   * currently observed mirroring that node - or {@code null} if any step of that chain is
   * unresolvable (the row has no mirrored node, its node's classId hasn't been matched yet, or
   * no row has been observed mirroring the resolved node).
   */
  private String resolveMirroredRowId(String originalRowId) {
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
      this.sendNext();
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
