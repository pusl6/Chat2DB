
package ai.chat2db.spi.util;

import ai.chat2db.server.tools.base.excption.BusinessException;
import ai.chat2db.spi.enums.DataTypeEnum;
import ai.chat2db.spi.model.ExecuteResult;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.procedure.CreateProcedure;
import net.sf.jsqlparser.statement.select.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author jipengfei
 * @version : SqlUtils.java
 */
@Slf4j
public class SqlUtils {

    public static final String DEFAULT_TABLE_NAME = "table1";

    public static void buildCanEditResult(String sql, DbType dbType, ExecuteResult executeResult) {
        try {
            Statement statement;
            if (DbType.sqlserver.equals(dbType)) {
                statement = CCJSqlParserUtil.parse(sql, ccjSqlParser -> ccjSqlParser.withSquareBracketQuotation(true));
            } else {
                statement = CCJSqlParserUtil.parse(sql);
            }
            if (statement instanceof Select) {
                Select select = (Select) statement;
                PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                if (plainSelect.getJoins() == null && plainSelect.getFromItem() != null) {
                    for (SelectItem item : plainSelect.getSelectItems()) {
                        if (item instanceof SelectExpressionItem) {
                            SelectExpressionItem expressionItem = (SelectExpressionItem) item;
                            if (expressionItem.getAlias() != null) {
                                //canEdit = false; // 找到了一个别名
                                executeResult.setCanEdit(false);
                                return;
                            }
                            if (item instanceof SelectExpressionItem) {
                                SelectExpressionItem selectExpressionItem = (SelectExpressionItem) item;
                                // if the expression is a function
                                if (selectExpressionItem.getExpression() instanceof Function) {
                                    Function function = (Function) selectExpressionItem.getExpression();
                                    // Check if the function is "COUNT"
                                    if ("COUNT".equalsIgnoreCase(function.getName())) {
                                        executeResult.setCanEdit(false);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    executeResult.setCanEdit(true);
                    SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
                    if ((sqlStatement instanceof SQLSelectStatement sqlSelectStatement)) {
                        SQLExprTableSource sqlExprTableSource = (SQLExprTableSource) getSQLExprTableSource(
                                sqlSelectStatement.getSelect().getFirstQueryBlock().getFrom());
                        executeResult.setTableName(getMetaDataTableName(sqlExprTableSource.getCatalog(), sqlExprTableSource.getSchema(), sqlExprTableSource.getTableName()));
                    }
                } else {
                    executeResult.setCanEdit(false);
                }
            }
        } catch (Exception e) {
            log.error("buildCanEditResult error", e);
            executeResult.setCanEdit(false);
        }
    }

    private static String getMetaDataTableName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(name -> name).collect(Collectors.joining("."));
    }

    public static String formatSQLString(Object para) {
        return para != null ? " '" + para + "' " : null;
    }

    public static String getTableName(String sql, DbType dbType) {
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement sqlSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        SQLExprTableSource sqlExprTableSource = (SQLExprTableSource) getSQLExprTableSource(
                sqlSelectStatement.getSelect().getFirstQueryBlock().getFrom());
        if (sqlExprTableSource == null) {
            return DEFAULT_TABLE_NAME;
        }
        return sqlExprTableSource.getTableName();
    }

    private static SQLTableSource getSQLExprTableSource(SQLTableSource sqlTableSource) {
        if (sqlTableSource instanceof SQLExprTableSource sqlExprTableSource) {
            return sqlExprTableSource;
        } else if (sqlTableSource instanceof SQLJoinTableSource sqlJoinTableSource) {
            return getSQLExprTableSource(sqlJoinTableSource.getLeft());
        }
        return null;
    }

    private static final String DELIMITER_AFTER_REGEX = "^\\s*(?i)delimiter\\s+(\\S+)";
    private static final String DELIMITER_REGEX = "(?mi)^\\s*delimiter\\s*;?";

    private static final String EVENT_REGEX = "(?i)\\bcreate\\s+event\\b.*?\\bend\\b";

    public static List<String> parse(String sql, DbType dbType) {
        List<String> list = new ArrayList<>();
        try {
            if (StringUtils.isBlank(sql)) {
                return list;
            }
            if (DbType.mysql.equals(dbType) ||
                    DbType.oracle.equals(dbType) ||
                    DbType.oceanbase.equals(dbType)) {
                sql = updateNow(sql, dbType);
                return split(new SqlSplitProcessor(dbType, false,false), sql);
            }
//            sql = removeDelimiter(sql);
            if (StringUtils.isBlank(sql)) {
                return list;
            }
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            // Iterate through each statement
            for (Statement stmt : statements.getStatements()) {
                if (!(stmt instanceof CreateProcedure)) {
                    list.add(stmt.toString());
                }
            }
            if (CollectionUtils.isEmpty(list)) {
                list.add(sql);
            }
        } catch (Exception e) {
            try {
                return splitWithCreateEvent(sql, dbType);
            } catch (Exception e1) {
                return SQLParserUtils.splitAndRemoveComment(sql, dbType);
            }
        }
        return list;
    }

    private static String removeDelimiter(String str) {
        try {
            if (str.toUpperCase().contains("DELIMITER")) {
                Pattern pattern = Pattern.compile(DELIMITER_AFTER_REGEX, Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(str);
                while (matcher.find()) {
                    // 获取并打印 "DELIMITER" 后的第一个字符串
                    String mm = matcher.group(1);
                    if (!";".equals(mm)) {
                        str = str.replace(mm, "");
                    }
                }
            }
            return str.replaceAll(DELIMITER_REGEX, "");
        } catch (Exception e) {
            return str;
        }
    }

    private static List<String> splitWithCreateEvent(String str, DbType dbType) {
        List<String> list = new ArrayList<>();
        String sql = SQLParserUtils.removeComment(str, dbType).trim();
        Pattern pattern = Pattern.compile(EVENT_REGEX, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        StringBuilder stringBuilder = new StringBuilder();
        int lastEnd = 0; // 用于跟踪上一个匹配的结束位置
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                List<String> l = SQLParserUtils.split(sql.substring(lastEnd, matcher.start()), dbType);
                list.addAll(l);
            }
            list.add(matcher.group());
            lastEnd = matcher.end(); // 更新上一个匹配的结束位置
        }
        if (lastEnd < sql.length()) {
            List<String> l = SQLParserUtils.split(sql.substring(lastEnd), dbType);
            list.addAll(l);
        }
        return list;
    }


    private static String updateNow(String sql, DbType dbType) {
        if (StringUtils.isBlank(sql) || !DbType.mysql.equals(dbType)) {
            return sql;
        }
        if (sql.contains("default now()")) {
            return sql.replace("default now()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("DEFAULT now()")) {
            return sql.replace("DEFAULT now()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("default now ()")) {
            return sql.replace("default now ()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("DEFAULT now ()")) {
            return sql.replace("DEFAULT now ()", "DEFAULT CURRENT_TIMESTAMP");
        }
        return sql;
    }

    private static final String DEFAULT_VALUE = "CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT";

    public static String getSqlValue(String value, String dataType) {
        if (value == null || value == "") {
            return null;
        }
        if (DEFAULT_VALUE.equals(value)) {
            return "DEFAULT";
        }
        DataTypeEnum dataTypeEnum = DataTypeEnum.getByCode(dataType);
        return dataTypeEnum.getSqlValue(value);
    }


    public static boolean hasPageLimit(String sql, DbType dbType) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select selectStatement = (Select) statement;
                SelectBody selectBody = selectStatement.getSelectBody();
                // Check out common pagination methods
                if (selectBody instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) selectBody;
                    // CHECK LIMIT
                    if (plainSelect.getLimit() != null || plainSelect.getOffset() != null || plainSelect.getTop() != null || plainSelect.getFetch() != null) {
                        return true;
                    }
                    if (DbType.oracle.equals(dbType)) {
                        return sql.contains("ROWNUM") || sql.contains("rownum");
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static List<String> split(SqlSplitProcessor processor, String sql) {
        StringBuffer buffer = new StringBuffer();
        List<SplitSqlString> sqls = processor.split(buffer, sql);
        String bufferStr = buffer.toString();
        if (bufferStr.trim().length() != 0) {
            // if buffer is not empty, there will be some errors in syntax
            log.info("sql processor's buffer is not empty, there may be some errors. buffer={}", bufferStr);
            int lastSqlOffset;
            if (sqls.size() == 0) {
                int index = sql.indexOf(bufferStr.trim(), 0);
                lastSqlOffset = index == -1 ? 0 : index;
            } else {
                int from = sqls.get(sqls.size() - 1).getOffset() + sqls.get(sqls.size() - 1).getStr().length();
                int index = sql.indexOf(bufferStr.trim(), from);
                lastSqlOffset = index == -1 ? from : index;
            }
            sqls.add(new SplitSqlString(lastSqlOffset, bufferStr));
        }
        return sqls.stream().map(SplitSqlString::getStr).collect(Collectors.toList());
    }

    public static void main(String[] args) {
        String sql = "DELIMITER //\n" +
                "\n" +
                "CREATE TRIGGER YS_production_dispatch_list_update\n" +
                "BEFORE UPDATE ON production_dispatch_list\n" +
                "FOR EACH ROW\n" +
                "BEGIN\n" +
                "  DECLARE rework_count INT DEFAULT 0;\n" +
                "  DECLARE scrap_count INT DEFAULT 0;\n" +
                "  DECLARE qualified_rate DOUBLE;\n" +
                "\n" +
                "    IF NEW.actual_completion_time IS NOT NULL THEN\n" +
                "\n" +
                "        SELECT COUNT(1) \n" +
                "        INTO rework_count \n" +
                "        FROM (\n" +
                "            SELECT \n" +
                "                uct.unique_code zz1, \n" +
                "                fm.unique_code zz2, \n" +
                "                ROW_NUMBER() OVER (PARTITION BY uct.unique_code, fm.unique_code ORDER BY fm.id) js\n" +
                "            FROM (\n" +
                "                SELECT DISTINCT unique_code\n" +
                "                FROM mars_platform_prod.check_task\n" +
                "                WHERE list_id = NEW.id\n" +
                "            ) AS uct\n" +
                "            INNER JOIN mars_platform_prod.flow_mgr AS fm \n" +
                "            ON uct.unique_code = fm.unique_code \n" +
                "            AND fm.is_deleted = 0 \n" +
                "            AND handle_result = 3\n" +
                "        ) AS bg \n" +
                "        WHERE js = 1;\n" +
                "        SET NEW.Rework_number_YS = rework_count;\n" +
                "\n" +
                "\n" +
                "        SELECT COUNT(1) \n" +
                "        INTO scrap_count \n" +
                "        FROM (\n" +
                "            SELECT \n" +
                "                uct.unique_code zz1, \n" +
                "                fm.unique_code zz2, \n" +
                "                ROW_NUMBER() OVER (PARTITION BY uct.unique_code, fm.unique_code ORDER BY fm.id) js\n" +
                "            FROM (\n" +
                "                SELECT DISTINCT unique_code\n" +
                "                FROM mars_platform_prod.check_task\n" +
                "                WHERE list_id = NEW.id\n" +
                "            ) AS uct\n" +
                "            INNER JOIN mars_platform_prod.flow_mgr AS fm \n" +
                "            ON uct.unique_code = fm.unique_code \n" +
                "            AND fm.is_deleted = 0 \n" +
                "            AND handle_result = 5\n" +
                "        ) AS bg \n" +
                "        WHERE js = 1;\n" +
                "        SET NEW.Scrap_number_YS = scrap_count;\n" +
                "\n" +
                "        SELECT ROUND(SUM(CASE WHEN zz2 IS NULL THEN 1 ELSE 0 END) * 100.0 / COUNT(zz1), 2) \n" +
                "        INTO qualified_rate \n" +
                "        FROM (\n" +
                "            SELECT \n" +
                "                uct.unique_code zz1, \n" +
                "                fm.unique_code zz2, \n" +
                "                ROW_NUMBER() OVER (PARTITION BY uct.unique_code, fm.unique_code ORDER BY fm.id) js\n" +
                "            FROM (\n" +
                "                SELECT DISTINCT unique_code\n" +
                "                FROM mars_platform_prod.check_task\n" +
                "                WHERE list_id = NEW.id\n" +
                "            ) AS uct\n" +
                "            LEFT JOIN mars_platform_prod.flow_mgr AS fm \n" +
                "            ON uct.unique_code = fm.unique_code \n" +
                "            AND fm.is_deleted = 0\n" +
                "            AND fm.`approval_result` = 1\n" +
                "        ) AS bg \n" +
                "        WHERE js = 1;\n" +
                "        SET NEW.qualified_one_rate_YS = qualified_rate;\n" +
                "    END IF;\n" +
                "END //\n" +
                "\n" +
                "DELIMITER ;\n" +
                "\n" +
                "\n" + "select * from t1;select * from t2";
        List<String> offsetStrings =  parse(sql, DbType.mysql);
        System.out.println(offsetStrings);
    }

}