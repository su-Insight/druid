/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.wall;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlHintStatement;
import com.alibaba.druid.sql.parser.*;
import com.alibaba.druid.sql.visitor.ExportParameterVisitor;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import com.alibaba.druid.util.ConcurrentLruCache;
import com.alibaba.druid.util.Utils;
import com.alibaba.druid.wall.spi.WallVisitorUtils;
import com.alibaba.druid.wall.violation.ErrorCode;
import com.alibaba.druid.wall.violation.IllegalSQLObjectViolation;
import com.alibaba.druid.wall.violation.SyntaxErrorViolation;

import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.alibaba.druid.util.JdbcSqlStatUtils.get;

public abstract class WallProvider {
    private String name;

    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>(
            1,
            0.75f,
            1);

    private boolean whiteListEnable = true;

    /**
     * 8k
     */
    private static final int MAX_SQL_LENGTH = 8192;

    private static final int WHITE_SQL_MAX_SIZE = 1024;

    // public for testing
    public static final int BLACK_SQL_MAX_SIZE = 256;

    private static final int MERGED_SQL_CACHE_SIZE = 256;

    private boolean blackListEnable = true;

    private final ConcurrentLruCache<String, WallSqlStat> whiteList = new ConcurrentLruCache<>(WHITE_SQL_MAX_SIZE);
    private final ConcurrentLruCache<String, WallSqlStat> blackList = new ConcurrentLruCache<>(BLACK_SQL_MAX_SIZE);
    private final ConcurrentLruCache<String, MergedSqlResult> mergedSqlCache = new ConcurrentLruCache<>(MERGED_SQL_CACHE_SIZE);

    protected final WallConfig config;

    private static final ThreadLocal<Boolean> privileged = new ThreadLocal<Boolean>();

    private final ConcurrentMap<String, WallFunctionStat> functionStats = new ConcurrentHashMap<String, WallFunctionStat>(
            16,
            0.75f,
            1);
    private final ConcurrentMap<String, WallTableStat> tableStats = new ConcurrentHashMap<String, WallTableStat>(
            16,
            0.75f,
            1);

    public final WallDenyStat commentDeniedStat = new WallDenyStat();

    protected DbType dbType;
    protected final AtomicLong checkCount = new AtomicLong();
    protected final AtomicLong hardCheckCount = new AtomicLong();
    protected final AtomicLong whiteListHitCount = new AtomicLong();
    protected final AtomicLong blackListHitCount = new AtomicLong();
    protected final AtomicLong syntaxErrorCount = new AtomicLong();
    protected final AtomicLong violationCount = new AtomicLong();
    protected final AtomicLong violationEffectRowCount = new AtomicLong();

    public WallProvider(WallConfig config) {
        this.config = config;
    }

    public WallProvider(WallConfig config, String dbType) {
        this(config, DbType.of(dbType));
    }

    public WallProvider(WallConfig config, DbType dbType) {
        this.config = config;
        this.dbType = dbType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void reset() {
        this.checkCount.set(0);
        this.hardCheckCount.set(0);
        this.violationCount.set(0);
        this.whiteListHitCount.set(0);
        this.blackListHitCount.set(0);
        this.clearWhiteList();
        this.clearBlackList();
        this.functionStats.clear();
        this.tableStats.clear();
    }

    public ConcurrentMap<String, WallTableStat> getTableStats() {
        return this.tableStats;
    }

    public ConcurrentMap<String, WallFunctionStat> getFunctionStats() {
        return this.functionStats;
    }

    public WallSqlStat getSqlStat(String sql) {
        WallSqlStat sqlStat = this.getWhiteSql(sql);

        if (sqlStat == null) {
            sqlStat = this.getBlackSql(sql);
        }

        return sqlStat;
    }

    public WallTableStat getTableStat(String tableName) {
        String lowerCaseName = tableName.toLowerCase();
        if (lowerCaseName.startsWith("`") && lowerCaseName.endsWith("`")) {
            lowerCaseName = lowerCaseName.substring(1, lowerCaseName.length() - 1);
        }

        return getTableStatWithLowerName(lowerCaseName);
    }

    public void addUpdateCount(WallSqlStat sqlStat, long updateCount) {
        sqlStat.addUpdateCount(updateCount);

        Map<String, WallSqlTableStat> sqlTableStats = sqlStat.getTableStats();
        if (sqlTableStats == null) {
            return;
        }

        for (Map.Entry<String, WallSqlTableStat> entry : sqlTableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = this.getTableStat(tableName);
            if (tableStat == null) {
                continue;
            }

            WallSqlTableStat sqlTableStat = entry.getValue();

            if (sqlTableStat.getDeleteCount() > 0) {
                tableStat.addDeleteDataCount(updateCount);
            } else if (sqlTableStat.getUpdateCount() > 0) {
                tableStat.addUpdateDataCount(updateCount);
            } else if (sqlTableStat.getInsertCount() > 0) {
                tableStat.addInsertDataCount(updateCount);
            }
        }
    }

    public void addFetchRowCount(WallSqlStat sqlStat, long fetchRowCount) {
        sqlStat.addAndFetchRowCount(fetchRowCount);

        Map<String, WallSqlTableStat> sqlTableStats = sqlStat.getTableStats();
        if (sqlTableStats == null) {
            return;
        }

        for (Map.Entry<String, WallSqlTableStat> entry : sqlTableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = this.getTableStat(tableName);
            if (tableStat == null) {
                continue;
            }

            WallSqlTableStat sqlTableStat = entry.getValue();

            if (sqlTableStat.getSelectCount() > 0) {
                tableStat.addFetchRowCount(fetchRowCount);
            }
        }
    }

    public WallTableStat getTableStatWithLowerName(String lowerCaseName) {
        WallTableStat stat = tableStats.get(lowerCaseName);
        if (stat == null) {
            if (tableStats.size() > 10000) {
                return null;
            }

            tableStats.putIfAbsent(lowerCaseName, new WallTableStat());
            stat = tableStats.get(lowerCaseName);
        }
        return stat;
    }

    public WallFunctionStat getFunctionStat(String functionName) {
        String lowerCaseName = functionName.toLowerCase();
        return getFunctionStatWithLowerName(lowerCaseName);
    }

    public WallFunctionStat getFunctionStatWithLowerName(String lowerCaseName) {
        WallFunctionStat stat = functionStats.get(lowerCaseName);
        if (stat == null) {
            if (functionStats.size() > 10000) {
                return null;
            }

            functionStats.putIfAbsent(lowerCaseName, new WallFunctionStat());
            stat = functionStats.get(lowerCaseName);
        }
        return stat;
    }

    public WallConfig getConfig() {
        return config;
    }

    public WallSqlStat addWhiteSql(String sql, Map<String, WallSqlTableStat> tableStats,
                                   Map<String, WallSqlFunctionStat> functionStats, boolean syntaxError) {
        if (!whiteListEnable) {
            WallSqlStat stat = new WallSqlStat(tableStats, functionStats, syntaxError);
            return stat;
        }

        String mergedSql = getMergedSqlNullableIfParameterizeError(sql);
        if (mergedSql == null) {
            WallSqlStat stat = new WallSqlStat(tableStats, functionStats, syntaxError);
            stat.incrementAndGetExecuteCount();
            return stat;
        }

        WallSqlStat wallSqlStat = whiteList.computeIfAbsent(mergedSql, key -> {
            WallSqlStat newStat = new WallSqlStat(tableStats, functionStats, syntaxError);
            newStat.setSample(sql);
            return newStat;
        });

        wallSqlStat.incrementAndGetExecuteCount();
        return wallSqlStat;
    }

    public WallSqlStat addBlackSql(String sql, Map<String, WallSqlTableStat> tableStats,
                                   Map<String, WallSqlFunctionStat> functionStats, List<Violation> violations,
                                   boolean syntaxError) {
        if (!blackListEnable) {
            return new WallSqlStat(tableStats, functionStats, violations, syntaxError);
        }

        String mergedSql = getMergedSqlNullableIfParameterizeError(sql);
        WallSqlStat wallSqlStat = blackList.computeIfAbsent(Utils.getIfNull(mergedSql, sql),
                key -> {
                    WallSqlStat wallStat = new WallSqlStat(tableStats, functionStats, violations, syntaxError);
                    wallStat.setSample(sql);
                    return wallStat;
                });

        wallSqlStat.incrementAndGetExecuteCount();
        return wallSqlStat;
    }

    private String getMergedSqlNullableIfParameterizeError(String sql) {
        MergedSqlResult mergedSqlResult = mergedSqlCache.get(sql);
        if (mergedSqlResult != null) {
            return mergedSqlResult.mergedSql;
        }
        try {
            String mergedSql = ParameterizedOutputVisitorUtils.parameterize(sql, dbType);
            if (sql.length() < MAX_SQL_LENGTH) {
                mergedSqlCache.computeIfAbsent(sql, key -> MergedSqlResult.success(mergedSql));
            }
            return mergedSql;
        } catch (Exception ex) {
            // skip
            mergedSqlCache.computeIfAbsent(sql, key -> MergedSqlResult.FAILED);
            return null;
        }
    }

    public Set<String> getWhiteList() {
        Set<String> hashSet = new HashSet<>();
        Set<String> whiteListKeys = whiteList.keys();
        if (!whiteListKeys.isEmpty()) {
            hashSet.addAll(whiteListKeys);
        }
        return Collections.unmodifiableSet(hashSet);
    }

    public Set<String> getSqlList() {
        Set<String> hashSet = new HashSet<>();
        Set<String> whiteListKeys = whiteList.keys();
        if (!whiteListKeys.isEmpty()) {
            hashSet.addAll(whiteListKeys);
        }

        Set<String> blackMergedListKeys = blackList.keys();
        if (!blackMergedListKeys.isEmpty()) {
            hashSet.addAll(blackMergedListKeys);
        }

        return Collections.unmodifiableSet(hashSet);
    }

    public Set<String> getBlackList() {
        Set<String> hashSet = new HashSet<>();
        Set<String> blackMergedListKeys = blackList.keys();
        if (!blackMergedListKeys.isEmpty()) {
            hashSet.addAll(blackMergedListKeys);
        }
        return Collections.unmodifiableSet(hashSet);
    }

    public void clearCache() {
        whiteList.clear();
        blackList.clear();
        mergedSqlCache.clear();
    }

    public void clearWhiteList() {
        whiteList.clear();
    }

    public void clearBlackList() {
        blackList.clear();
    }

    public WallSqlStat getWhiteSql(String sql) {
        String cacheKey = Utils.getIfNull(getMergedSqlNullableIfParameterizeError(sql), sql);
        return whiteList.get(cacheKey);
    }

    public WallSqlStat getBlackSql(String sql) {
        String cacheKey = Utils.getIfNull(getMergedSqlNullableIfParameterizeError(sql), sql);
        return blackList.get(cacheKey);
    }

    public boolean whiteContains(String sql) {
        return getWhiteSql(sql) != null;
    }

    public abstract SQLStatementParser createParser(String sql);

    public abstract WallVisitor createWallVisitor();

    public abstract ExportParameterVisitor createExportParameterVisitor();

    public boolean checkValid(String sql) {
        WallContext originalContext = WallContext.current();

        try {
            WallContext.create(dbType);
            WallCheckResult result = checkInternal(sql);
            return result
                    .getViolations()
                    .isEmpty();
        } finally {
            if (originalContext == null) {
                WallContext.clearContext();
            }
        }
    }

    public void incrementCommentDeniedCount() {
        this.commentDeniedStat.incrementAndGetDenyCount();
    }

    public boolean checkDenyFunction(String functionName) {
        if (functionName == null) {
            return true;
        }

        functionName = functionName.toLowerCase();

        return !getConfig().getDenyFunctions().contains(functionName);

    }

    public boolean checkDenySchema(String schemaName) {
        if (schemaName == null) {
            return true;
        }

        if (!this.config.isSchemaCheck()) {
            return true;
        }

        schemaName = schemaName.toLowerCase();
        return !getConfig().getDenySchemas().contains(schemaName);

    }

    public boolean checkDenyTable(String tableName) {
        if (tableName == null) {
            return true;
        }

        tableName = WallVisitorUtils.form(tableName);
        return !getConfig().getDenyTables().contains(tableName);

    }

    public boolean checkReadOnlyTable(String tableName) {
        if (tableName == null) {
            return true;
        }

        tableName = WallVisitorUtils.form(tableName);
        return !getConfig().isReadOnly(tableName);

    }

    public WallDenyStat getCommentDenyStat() {
        return this.commentDeniedStat;
    }

    public WallCheckResult check(String sql) {
        WallContext originalContext = WallContext.current();

        try {
            WallContext.createIfNotExists(dbType);
            return checkInternal(sql);
        } finally {
            if (originalContext == null) {
                WallContext.clearContext();
            }
        }
    }

    private WallCheckResult checkInternal(String sql) {
        checkCount.incrementAndGet();

        WallContext context = WallContext.current();

        if (config.isDoPrivilegedAllow() && ispPrivileged()) {
            WallCheckResult checkResult = new WallCheckResult();
            checkResult.setSql(sql);
            return checkResult;
        }

        // first step, check whiteList
        boolean mulltiTenant = config.getTenantTablePattern() != null && config.getTenantTablePattern().length() > 0;
        if (!mulltiTenant) {
            WallCheckResult checkResult = checkWhiteAndBlackList(sql);
            if (checkResult != null) {
                checkResult.setSql(sql);
                return checkResult;
            }
        }

        hardCheckCount.incrementAndGet();
        final List<Violation> violations = new ArrayList<Violation>();
        List<SQLStatement> statementList = new ArrayList<SQLStatement>();
        boolean syntaxError = false;
        boolean endOfComment = false;
        try {
            SQLStatementParser parser = createParser(sql);
            parser.getLexer().setCommentHandler(WallCommentHandler.instance);

            if (!config.isCommentAllow()) {
                parser.getLexer().setAllowComment(false); // deny comment
            }
            if (!config.isCompleteInsertValuesCheck()) {
                parser.setParseCompleteValues(false);
                parser.setParseValuesSize(config.getInsertValuesCheckSize());
            }
            if (config.isHintAllow()) {
                parser.config(SQLParserFeature.StrictForWall, false);
            }
            parser.parseStatementList(statementList);

            final Token lastToken = parser.getLexer().token();
            if (lastToken != Token.EOF && config.isStrictSyntaxCheck()) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.SYNTAX_ERROR, "not terminal sql, token "
                        + lastToken, sql));
            }
            endOfComment = parser.getLexer().isEndOfComment();
        } catch (NotAllowCommentException e) {
            violations.add(new IllegalSQLObjectViolation(ErrorCode.COMMENT_STATEMENT_NOT_ALLOW, "comment not allow", sql));
            incrementCommentDeniedCount();
        } catch (ParserException e) {
            syntaxErrorCount.incrementAndGet();
            syntaxError = true;
            if (config.isStrictSyntaxCheck()) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        } catch (Exception e) {
            if (config.isStrictSyntaxCheck()) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        }

        if (statementList.size() > 1 && !config.isMultiStatementAllow()) {
            violations.add(new IllegalSQLObjectViolation(ErrorCode.MULTI_STATEMENT, "multi-statement not allow", sql));
        }

        WallVisitor visitor = createWallVisitor();
        visitor.setSqlEndOfComment(endOfComment);

        if (statementList.size() > 0) {
            boolean lastIsHint = false;
            for (int i = 0; i < statementList.size(); i++) {
                SQLStatement stmt = statementList.get(i);
                if ((i == 0 || lastIsHint) && stmt instanceof MySqlHintStatement) {
                    lastIsHint = true;
                    continue;
                }
                try {
                    stmt.accept(visitor);
                } catch (ParserException e) {
                    violations.add(new SyntaxErrorViolation(e, sql));
                }
            }
        }

        if (visitor.getViolations().size() > 0) {
            violations.addAll(visitor.getViolations());
        }

        Map<String, WallSqlTableStat> tableStat = context.getTableStats();

        boolean updateCheckHandlerEnable = false;
        {
            WallUpdateCheckHandler updateCheckHandler = config.getUpdateCheckHandler();
            if (updateCheckHandler != null) {
                for (SQLStatement stmt : statementList) {
                    if (stmt instanceof SQLUpdateStatement) {
                        SQLUpdateStatement updateStmt = (SQLUpdateStatement) stmt;
                        SQLName table = updateStmt.getTableName();
                        if (table != null) {
                            String tableName = table.getSimpleName();
                            Set<String> updateCheckColumns = config.getUpdateCheckTable(tableName);
                            if (updateCheckColumns != null && updateCheckColumns.size() > 0) {
                                updateCheckHandlerEnable = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        WallSqlStat sqlStat = null;
        if (violations.size() > 0) {
            violationCount.incrementAndGet();

            if ((!updateCheckHandlerEnable) && sql.length() < MAX_SQL_LENGTH) {
                sqlStat = addBlackSql(sql, tableStat, context.getFunctionStats(), violations, syntaxError);
            }
        } else {
            if ((!updateCheckHandlerEnable) && sql.length() < MAX_SQL_LENGTH) {
                boolean selectLimit = false;
                if (config.getSelectLimit() > 0) {
                    for (SQLStatement stmt : statementList) {
                        if (stmt instanceof SQLSelectStatement) {
                            selectLimit = true;
                            break;
                        }
                    }
                }

                if (!selectLimit) {
                    sqlStat = addWhiteSql(sql, tableStat, context.getFunctionStats(), syntaxError);
                }
            }
        }

        if (sqlStat == null && updateCheckHandlerEnable) {
            sqlStat = new WallSqlStat(tableStat, context.getFunctionStats(), violations, syntaxError);
        }

        Map<String, WallSqlTableStat> tableStats = null;
        Map<String, WallSqlFunctionStat> functionStats = null;
        if (context != null) {
            tableStats = context.getTableStats();
            functionStats = context.getFunctionStats();
            recordStats(tableStats, functionStats);
        }

        WallCheckResult result;
        if (sqlStat != null) {
            context.setSqlStat(sqlStat);
            result = new WallCheckResult(sqlStat, statementList);
        } else {
            result = new WallCheckResult(null, violations, tableStats, functionStats, statementList, syntaxError);
        }

        String resultSql;
        if (visitor.isSqlModified()) {
            resultSql = SQLUtils.toSQLString(statementList, dbType);
        } else {
            resultSql = sql;
        }
        result.setSql(resultSql);

        result.setUpdateCheckItems(visitor.getUpdateCheckItems());

        return result;
    }

    private WallCheckResult checkWhiteAndBlackList(String sql) {
        if (config.getUpdateCheckHandler() != null) {
            return null;
        }

        // check white list first
        if (whiteListEnable) {
            WallSqlStat sqlStat = getWhiteSql(sql);
            if (sqlStat != null) {
                whiteListHitCount.incrementAndGet();
                sqlStat.incrementAndGetExecuteCount();

                if (sqlStat.isSyntaxError()) {
                    syntaxErrorCount.incrementAndGet();
                }

                recordStats(sqlStat.getTableStats(), sqlStat.getFunctionStats());
                WallContext context = WallContext.current();
                if (context != null) {
                    context.setSqlStat(sqlStat);
                }
                return new WallCheckResult(sqlStat);
            }
        }

        // check black list
        if (blackListEnable) {
            WallSqlStat sqlStat = getBlackSql(sql);
            if (sqlStat != null) {
                blackListHitCount.incrementAndGet();
                violationCount.incrementAndGet();

                if (sqlStat.isSyntaxError()) {
                    syntaxErrorCount.incrementAndGet();
                }

                sqlStat.incrementAndGetExecuteCount();
                recordStats(sqlStat.getTableStats(), sqlStat.getFunctionStats());

                return new WallCheckResult(sqlStat);
            }
        }

        return null;
    }

    void recordStats(Map<String, WallSqlTableStat> tableStats, Map<String, WallSqlFunctionStat> functionStats) {
        if (tableStats != null) {
            for (Map.Entry<String, WallSqlTableStat> entry : tableStats.entrySet()) {
                String tableName = entry.getKey();
                WallSqlTableStat sqlTableStat = entry.getValue();
                WallTableStat tableStat = getTableStat(tableName);
                if (tableStat != null) {
                    tableStat.addSqlTableStat(sqlTableStat);
                }
            }
        }
        if (functionStats != null) {
            for (Map.Entry<String, WallSqlFunctionStat> entry : functionStats.entrySet()) {
                String tableName = entry.getKey();
                WallSqlFunctionStat sqlTableStat = entry.getValue();
                WallFunctionStat functionStat = getFunctionStatWithLowerName(tableName);
                if (functionStat != null) {
                    functionStat.addSqlFunctionStat(sqlTableStat);
                }
            }
        }
    }

    public static boolean ispPrivileged() {
        Boolean value = privileged.get();
        if (value == null) {
            return false;
        }

        return value;
    }

    public static <T> T doPrivileged(PrivilegedAction<T> action) {
        final Boolean original = privileged.get();
        privileged.set(Boolean.TRUE);
        try {
            return action.run();
        } finally {
            privileged.set(original);
        }
    }

    private static final ThreadLocal<Object> tenantValueLocal = new ThreadLocal<Object>();

    public static void setTenantValue(Object value) {
        tenantValueLocal.set(value);
    }

    public static Object getTenantValue() {
        return tenantValueLocal.get();
    }

    public long getWhiteListHitCount() {
        return whiteListHitCount.get();
    }

    public long getBlackListHitCount() {
        return blackListHitCount.get();
    }

    public long getSyntaxErrorCount() {
        return syntaxErrorCount.get();
    }

    public long getCheckCount() {
        return checkCount.get();
    }

    public long getViolationCount() {
        return violationCount.get();
    }

    public long getHardCheckCount() {
        return hardCheckCount.get();
    }

    public long getViolationEffectRowCount() {
        return violationEffectRowCount.get();
    }

    public void addViolationEffectRowCount(long rowCount) {
        violationEffectRowCount.addAndGet(rowCount);
    }

    public static class WallCommentHandler implements Lexer.CommentHandler {
        public static final WallCommentHandler instance = new WallCommentHandler();

        @Override
        public boolean handle(Token lastToken, String comment) {
            if (lastToken == null) {
                return false;
            }

            switch (lastToken) {
                case SELECT:
                case INSERT:
                case DELETE:
                case UPDATE:
                case TRUNCATE:
                case SET:
                case CREATE:
                case ALTER:
                case DROP:
                case SHOW:
                case REPLACE:
                    return true;
                default:
                    break;
            }

            WallContext context = WallContext.current();
            if (context != null) {
                context.incrementCommentCount();
            }

            return false;
        }
    }

    public WallProviderStatValue getStatValue(boolean reset) {
        WallProviderStatValue statValue = new WallProviderStatValue();

        statValue.setName(name);
        statValue.setCheckCount(get(checkCount, reset));
        statValue.setHardCheckCount(get(hardCheckCount, reset));
        statValue.setViolationCount(get(violationCount, reset));
        statValue.setViolationEffectRowCount(get(violationEffectRowCount, reset));
        statValue.setBlackListHitCount(get(blackListHitCount, reset));
        statValue.setWhiteListHitCount(get(whiteListHitCount, reset));
        statValue.setSyntaxErrorCount(get(syntaxErrorCount, reset));

        for (Map.Entry<String, WallTableStat> entry : this.tableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = entry.getValue();

            WallTableStatValue tableStatValue = tableStat.getStatValue(reset);

            if (tableStatValue.getTotalExecuteCount() == 0) {
                continue;
            }

            tableStatValue.setName(tableName);

            statValue.getTables().add(tableStatValue);
        }

        for (Map.Entry<String, WallFunctionStat> entry : this.functionStats.entrySet()) {
            String functionName = entry.getKey();
            WallFunctionStat functionStat = entry.getValue();

            WallFunctionStatValue functionStatValue = functionStat.getStatValue(reset);

            if (functionStatValue.getInvokeCount() == 0) {
                continue;
            }
            functionStatValue.setName(functionName);

            statValue.getFunctions().add(functionStatValue);
        }

        whiteList.forEach((sql, sqlStat) -> {
            WallSqlStatValue sqlStatValue = sqlStat.getStatValue(reset);

            if (sqlStatValue.getExecuteCount() == 0) {
                return;
            }

            sqlStatValue.setSql(sql);

            long sqlHash = sqlStat.getSqlHash();
            if (sqlHash == 0) {
                sqlHash = Utils.fnv_64(sql);
                sqlStat.setSqlHash(sqlHash);
            }
            sqlStatValue.setSqlHash(sqlHash);

            statValue.getWhiteList().add(sqlStatValue);
        });

        blackList.forEach((sql, sqlStat) -> {
            WallSqlStatValue sqlStatValue = sqlStat.getStatValue(reset);

            if (sqlStatValue.getExecuteCount() == 0) {
                return;
            }

            sqlStatValue.setSql(sql);
            statValue.getBlackList().add(sqlStatValue);
        });

        return statValue;
    }

    public Map<String, Object> getStatsMap() {
        return getStatValue(false).toMap();
    }

    public boolean isWhiteListEnable() {
        return whiteListEnable;
    }

    public void setWhiteListEnable(boolean whiteListEnable) {
        this.whiteListEnable = whiteListEnable;
    }

    public boolean isBlackListEnable() {
        return blackListEnable;
    }

    public void setBlackListEnable(boolean blackListEnable) {
        this.blackListEnable = blackListEnable;
    }

    private static class MergedSqlResult {
        public static final MergedSqlResult FAILED = new MergedSqlResult(null);

        final String mergedSql;

        MergedSqlResult(String mergedSql) {
            this.mergedSql = mergedSql;
        }

        public static MergedSqlResult success(String mergedSql) {
            return new MergedSqlResult(mergedSql);
        }
    }
}
