/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 Elastic and contributors
 * %%
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
 * #L%
 */
package co.elastic.webapp;

import co.elastic.apm.api.ElasticApm;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class TestServlet extends HttpServlet {

    private final Connection connection;

    public TestServlet() throws Exception {
        // registers the driver in the DriverManager
        Class.forName("org.h2.Driver");
        connection = DriverManager.getConnection("jdbc:h2:mem:test", "user", "");
        final Statement connectionStatement = connection.createStatement();
        connectionStatement.execute("CREATE TABLE ELASTIC_APM (FOO INT, BAR VARCHAR(255))");
        connection.createStatement().execute("INSERT INTO ELASTIC_APM (FOO, BAR) VALUES (1, 'Hello World!')");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!ElasticApm.currentTransaction().isSampled()) {
            throw new IllegalStateException("Current transaction is not sampled: " + ElasticApm.currentTransaction());
        }
        try {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM ELASTIC_APM WHERE FOO=$1");
            preparedStatement.setInt(1, 1);
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            resp.getWriter().append(resultSet.getString("bar"));
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }
}
