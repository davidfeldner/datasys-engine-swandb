package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inner equi-join: builds a hash table from the build child, streams the probe
 * child. A probe row that matches {@code k} build rows emits {@code k} output
 * rows, each the build row's columns followed by the probe row's columns, so
 * a left-deep chain emits columns in {@code FROM}-list order.
 *
 * <p>Keys are compared with Java {@code equals}: the binder has already
 * rejected a join whose two sides have different types, because a {@code
 * LONG} 3 and a {@code DOUBLE} 3.0 would never match.
 *
 * <p>{@link #close()} reports the three figures that make a join visible in
 * the log: rows drained from the build side, rows pulled from the probe side,
 * and rows emitted. {@code rowsOut} is the intermediate result size week 11
 * optimizes and week 12 measures.
 */
public final class HashJoinOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;

    /** The build side, key to every row that carries the key. */
    private final Map<Object, List<Object[]>> hashTable = new HashMap<>();

    /** The match list of the probe row currently being emitted, and its cursor. */
    private List<Object[]> matches;
    private int cursor;
    private Object[] probeRow;

    private long rowsBuild;
    private long rowsProbe;
    private long rowsOut;

    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
    }

    @Override
    public void open() {
        rowsBuild = 0;
        rowsProbe = 0;
        rowsOut = 0;
        matches = null;
        cursor = 0;
        probeRow = null;
        hashTable.clear();

        build.open();
        Object[] row;
        while ((row = build.next()) != null) {
            rowsBuild++;
            hashTable.computeIfAbsent(row[buildKey], key -> new ArrayList<>()).add(row);
        }
        probe.open();
    }

    @Override
    public Object[] next() {
        while (true) {
            if (matches != null && cursor < matches.size()) {
                Object[] buildRow = matches.get(cursor++);
                rowsOut++;
                return concatenate(buildRow, probeRow);
            }
            probeRow = probe.next();
            if (probeRow == null) return null;
            rowsProbe++;
            matches = hashTable.get(probeRow[probeKey]);
            cursor = 0;
        }
    }

    @Override
    public void close() {
        probe.close();
        build.close();
        LOGGER.debug("rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
    }

    private static Object[] concatenate(Object[] buildRow, Object[] probeRow) {
        Object[] row = new Object[buildRow.length + probeRow.length];
        System.arraycopy(buildRow, 0, row, 0, buildRow.length);
        System.arraycopy(probeRow, 0, row, buildRow.length, probeRow.length);
        return row;
    }

    // Package-private accessors: the planner test walks the tree it built.

    Operator build() {
        return build;
    }

    Operator probe() {
        return probe;
    }

    int buildKey() {
        return buildKey;
    }

    int probeKey() {
        return probeKey;
    }
}
