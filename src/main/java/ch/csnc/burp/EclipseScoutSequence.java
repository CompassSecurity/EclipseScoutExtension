package ch.csnc.burp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    void learnOriginal(Map<String, String> idToClassId) {
      idToClassId.forEach((id, classId) -> {
        this.originalIdToClassId.put(id, classId);
        this.originalIdsByClassId.computeIfAbsent(classId, key -> new ArrayList<>()).add(id);
      });
    }

    void learnReplay(Map<String, String> idToClassId) {
      idToClassId.forEach(
          (id, classId) -> this.replayIdsByClassId.computeIfAbsent(classId, key -> new ArrayList<>()).add(id));
    }

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

  public EclipseScoutSequence(List<EclipseScoutExchange> exchanges) {
    this.exchanges = exchanges.stream().map(EclipseScoutExchange::clone).toList();
    for (var exchange : this.exchanges) {
      this.adapterResolver.learnOriginal(exchange.responseAdapterClassIds());
      this.nodeResolver.learnOriginal(exchange.responseNodeClassIds());
      this.originalRowIdToNodeId.putAll(exchange.responseRowNodeIds());
    }
  }

  public boolean hasNext() {
    return this.position < this.exchanges.size();
  }

  /**
   * Returns the next exchange in display order, propagating the UI session id from the
   * previous exchange, renumbering its body's "#" sequence number to match its position, and
   * remapping "target"/"nodeId"/"rowId" ids in its request via classId-based identity
   * matching: an id captured against the original session is resolved to whatever id that same
   * structural widget/node (same classId) - or, for rows, the node it mirrors - was assigned
   * during THIS replay run. This keeps the sequence pointed at the right widget even when
   * replay assigns different numeric ids - e.g. because an earlier step in the sequence added
   * something that shifted every id created after it.
   *
   * <p>Relies on the previous exchange already having been sent (its response set) before this
   * is called - true both for the plain send loop ({@code next().send()} repeated) and for
   * {@link #advanceTo(int)}, and required already for UI session id propagation above.
   */
  public EclipseScoutExchange next() {
    var exchange = this.exchanges.get(this.position);
    if (this.position > 0) {
      var previous = this.exchanges.get(this.position - 1);
      exchange.setUiSessionId(previous.getUiSessionId());
      this.adapterResolver.learnReplay(previous.responseAdapterClassIds());
      this.nodeResolver.learnReplay(previous.responseNodeClassIds());
      previous.responseRowNodeIds()
          .forEach((rowId, nodeId) -> this.replayNodeIdToRowId.putIfAbsent(nodeId, rowId));
    }
    exchange.renumberSequenceNr(this.position);
    exchange.remapIds(this.adapterResolver::resolve, this.nodeResolver::resolve, this::resolveRowId);
    this.position++;
    return exchange;
  }

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
    while (this.position < index) {
      this.next().send();
    }
    return this.next();
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
