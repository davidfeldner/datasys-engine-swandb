package dk.itu.swandb.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.itu.swandb.LogFormat;

/**
 * Inner equi-join: builds a hash table from the build child, streams the probe
 * child. A probe row that matches {@code k} build rows emits {@code k} output
 * rows, each the build row's columns followed by the probe row's columns.
 *
 * <p>Keys are compared with Java {@code equals}: the binder has already
 * rejected a join whose two sides have different types, because a {@code
 * LONG} 3 and a {@code DOUBLE} 3.0 would never match.
 *
 * <p>Since Exercise 11 the operator is handed its plan-time estimate and
 * which side each input is, so {@link #close()} writes the one line that
 * makes the optimizer measurable: the estimated output next to the actual
 * one.
 *
 * <pre>
 * join=1 tables=products&lt;orders estRows=4 rowsBuild=2 rowsProbe=4 rowsOut=4
 * </pre>
 *
 * {@code rowsOut} is the size of an intermediate result — the number week 11
 * optimizes and week 12 measures — and {@code estRows} against it is the
 * estimate error.
 */
public final class HashJoinOperator implements Operator {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashJoinOperator.class);

    private final Operator build;
    private final Operator probe;
    private final int buildKey;
    private final int probeKey;
    private final JoinEstimate estimate;

    /** The build side, key to every row that carries the key. */
    private final Map<Object, List<Object[]>> hashTable = new HashMap<>();

    /** The match list of the probe row currently being emitted, and its cursor. */
    private List<Object[]> matches;
    private int cursor;
    private Object[] probeRow;

    private long rowsBuild;
    private long rowsProbe;
    private long rowsOut;

    /** Without an estimate: the operator's own tests build it this way. */
    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey) {
        this(build, probe, buildKey, probeKey, null);
    }

    /**
     * @param estimate the plan-time estimate and the inputs' names, or
     *                 {@code null} when the caller has none
     */
    HashJoinOperator(Operator build, Operator probe, int buildKey, int probeKey,
                     JoinEstimate estimate) {
        this.build = build;
        this.probe = probe;
        this.buildKey = buildKey;
        this.probeKey = probeKey;
        this.estimate = estimate;
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
        if (estimate == null) {
            LOGGER.debug("rowsBuild={} rowsProbe={} rowsOut={}", rowsBuild, rowsProbe, rowsOut);
            return;
        }
        // The line Exercise 11 section 5 asks for: estimate and actual output
        // on one line, so the error is recoverable per join, and attributable
        // to the statement through the CSV log's statementNumber field.
        LOGGER.debug("join={} tables={} estRows={} rowsBuild={} rowsProbe={} rowsOut={} crossProduct={}",
                estimate.number(), LogFormat.sanitize(estimate.tables()), estimate.estRows(),
                rowsBuild, rowsProbe, rowsOut, estimate.crossProduct());
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

    /** The plan-time estimate and input roles, or {@code null}. */
    JoinEstimate estimate() {
        return estimate;
    }
}
