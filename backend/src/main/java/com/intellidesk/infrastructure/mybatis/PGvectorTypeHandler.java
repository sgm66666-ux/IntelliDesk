package com.intellidesk.infrastructure.mybatis;

import com.pgvector.PGvector;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PGvectorTypeHandler extends BaseTypeHandler<PGvector> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, PGvector parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public PGvector getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toPGvector(rs.getObject(columnName));
    }

    @Override
    public PGvector getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toPGvector(rs.getObject(columnIndex));
    }

    @Override
    public PGvector getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toPGvector(cs.getObject(columnIndex));
    }

    private PGvector toPGvector(Object obj) throws SQLException {
        if (obj == null) {
            return null;
        }
        if (obj instanceof PGvector pgvector) {
            return pgvector;
        }
        if (obj instanceof PGobject pgObject) {
            return new PGvector(pgObject.getValue());
        }
        throw new SQLException("Unsupported type for PGvector conversion: " + obj.getClass().getName());
    }
}