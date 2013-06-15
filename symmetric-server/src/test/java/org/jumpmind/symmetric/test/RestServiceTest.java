package org.jumpmind.symmetric.test;

import java.sql.Types;
import java.util.List;

import org.jumpmind.db.model.Column;
import org.jumpmind.db.model.Table;
import org.jumpmind.db.platform.DatabaseNamesConstants;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.model.Channel;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.IParameterService;
import org.jumpmind.symmetric.web.rest.NotAllowedException;
import org.jumpmind.symmetric.web.rest.RestService;
import org.jumpmind.symmetric.web.rest.model.Batch;
import org.jumpmind.symmetric.web.rest.model.BatchResult;
import org.jumpmind.symmetric.web.rest.model.BatchResults;
import org.jumpmind.symmetric.web.rest.model.PullDataResults;
import org.jumpmind.symmetric.web.rest.model.RegistrationInfo;
import org.jumpmind.util.FormatUtils;
import org.junit.Assert;

public class RestServiceTest extends AbstractTest {

    @Override
    protected Table[] getTables(String name) {
        Table a = new Table("a");
        a.addColumn(new Column("id", true, Types.INTEGER, -1, -1));
        a.addColumn(new Column("notes", false, Types.VARCHAR, 255, -1));
        a.addColumn(new Column("created", false, Types.TIMESTAMP, -1, -1));
        return new Table[] { a };
    }

    @Override
    protected String[] getGroupNames() {
        return new String[] { "server", "client" };
    }

    @Override
    protected void test(ISymmetricEngine rootServer, ISymmetricEngine clientServer)
            throws Exception {

        loadConfigAtRegistrationServer();

        RestService restService = getRegServer().getRestService();
        ISymmetricEngine engine = getRegServer().getEngine();
        IParameterService parameterService = engine.getParameterService();
        INodeService nodeService = engine.getNodeService();

        parameterService.saveParameter(ParameterConstants.REST_API_ENABLED, Boolean.TRUE,
                "unit_test");

        Assert.assertNotNull("Could not find the rest service in the application context",
                restService);

        List<Node> nodes = nodeService.findAllNodes();

        Assert.assertEquals("Expected there to only be one node registered", 1, nodes.size());
        Assert.assertEquals("The only node we expected to be registered is a server node",
                "server", nodes.get(0).getNodeGroupId());

        RegistrationInfo registrationInfo = restService.postRegisterNode("client", "client",
                DatabaseNamesConstants.SQLITE, "3.0", "hostName");

        Assert.assertNotNull("Registration should have returned a result object", registrationInfo);
        Assert.assertFalse("Registration should not have been open",
                registrationInfo.isRegistered());
        Assert.assertEquals("Expected there to only be one node registered", 1, nodes.size());

        engine.openRegistration("client", "client");

        registrationInfo = restService.postRegisterNode("client", "client",
                DatabaseNamesConstants.SQLITE, "3.0", "hostName");

        Assert.assertNotNull("Registration should have returned a result object", registrationInfo);
        Assert.assertTrue("Registration should have been open", registrationInfo.isRegistered());
        Assert.assertEquals("client", registrationInfo.getNodeId());

        try {
            restService.getPullData(registrationInfo.getNodeId(), "wrong password", false, false, true, null);
            Assert.fail("We should have received an exception");
        } catch (NotAllowedException ex) {
        }

        PullDataResults results = null;
        
        assertPullReturnsNoData(restService, registrationInfo);
        
        engine.getSqlTemplate().update("insert into a values(?, ?, ?)", 1, "this is a test", FormatUtils.parseDate("2013-06-08 00:00:00.000", FormatUtils.TIMESTAMP_PATTERNS));
        
        engine.route();
        
        results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), false, false, true, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(1, results.getNbrBatches());
        Assert.assertEquals(4, results.getBatches().get(0).getBatchId());
        
        log.info(results.getBatches().get(0).getSqlStatements().get(0));
        
        // pull a second time without acking.  should get the same results
        results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), false, false, false, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(1, results.getNbrBatches());
        Assert.assertEquals(4, results.getBatches().get(0).getBatchId());
        
        // test that when we don't request jdbc timestamp format sql statements come back in that format
        Assert.assertFalse(results.getBatches().get(0).getSqlStatements().get(0).contains("{ts '"));
        
        // make sure we have no delimited identifiers
        Assert.assertFalse(results.getBatches().get(0).getSqlStatements().get(0).contains("\""));
        
        engine.getSqlTemplate().update("update a set notes=? where id=?", "changed", 1);
        engine.getSqlTemplate().update("update a set notes=? where id=?", "changed again", 1);

        engine.route();
        
        results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), true, false, true, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(2, results.getNbrBatches());
        Assert.assertEquals(4, results.getBatches().get(0).getBatchId());
        Assert.assertEquals(5, results.getBatches().get(1).getBatchId());
        Assert.assertEquals(2, results.getBatches().get(1).getSqlStatements().size());
        
        // test that when we request jdbc timestamp format sql statements come back in that format
        Assert.assertTrue(results.getBatches().get(1).getSqlStatements().get(0).contains("{ts '"));
        
        // make sure we have delimited identifiers
        Assert.assertTrue(results.getBatches().get(1).getSqlStatements().get(0).contains("\""));
        log.info(results.getBatches().get(1).getSqlStatements().get(0));
        log.info(results.getBatches().get(1).getSqlStatements().get(1));
        
        BatchResults batchResults = new BatchResults();
        batchResults.getBatchResults().add(new BatchResult(registrationInfo.getNodeId(), 4, true));
        batchResults.getBatchResults().add(new BatchResult(registrationInfo.getNodeId(), 5, true));
        restService.putAcknowledgeBatch("server", registrationInfo.getNodePassword(), batchResults);
        
        assertPullReturnsNoData(restService, registrationInfo);
        
        engine.getSqlTemplate().update("insert into a values(?, ?, ?)", 2, "this is a test", FormatUtils.parseDate("2073-06-08 00:00:00.000", FormatUtils.TIMESTAMP_PATTERNS));
        engine.getSqlTemplate().update("insert into a values(?, ?, ?)", 3, "this is a test", FormatUtils.parseDate("2073-06-08 00:00:00.000", FormatUtils.TIMESTAMP_PATTERNS));
        engine.getSqlTemplate().update("update a set notes=? where id=?", "update to 2", 2);
        engine.getSqlTemplate().update("update a set notes=? where id=?", "update to 3", 3);
        engine.getSqlTemplate().update("update a set notes=? where id=?", "update 2 again", 2);
        
        engine.route();
        
        results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), false, true, true, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(1, results.getNbrBatches());
        List<String> sqls = results.getBatches().get(0).getSqlStatements();
        Assert.assertEquals(5, sqls.size());
        for (String sql : sqls) {
            log.info(sql);
            Assert.assertTrue(sql, sql.toLowerCase().startsWith("insert or replace"));
        }

        batchResults = new BatchResults();
        batchResults.getBatchResults().add(new BatchResult(registrationInfo.getNodeId(), results.getBatches().get(0).getBatchId(), true));
        restService.putAcknowledgeBatch("server", registrationInfo.getNodePassword(), batchResults);
        
        assertPullReturnsNoData(restService, registrationInfo);
        
        Channel channel = engine.getConfigurationService().getChannel("default");
        channel.setBatchAlgorithm("nontransactional");
        channel.setMaxBatchSize(1);
        engine.getConfigurationService().saveChannel(channel, true);
        
        engine.getSqlTemplate().update("delete from a");
        
        engine.route();
        
        results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), false, false, true, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(3, results.getNbrBatches());
        List<Batch> batches = results.getBatches();
        batchResults = new BatchResults();
        for (Batch batch : batches) {
            Assert.assertEquals(1, batch.getSqlStatements().size());
            Assert.assertTrue(batch.getSqlStatements().get(0).toLowerCase().startsWith("delete from"));
            batchResults.getBatchResults().add(new BatchResult(registrationInfo.getNodeId(), batch.getBatchId(), true));            
        }
        
        restService.putAcknowledgeBatch("server", registrationInfo.getNodePassword(), batchResults);

        assertPullReturnsNoData(restService, registrationInfo);
        
    }
    
    protected void assertPullReturnsNoData(RestService restService, RegistrationInfo registrationInfo) {
        PullDataResults results = restService.getPullData("server", registrationInfo.getNodeId(),
                registrationInfo.getNodePassword(), false, false, true, null);
        Assert.assertNotNull("Should have a non null results object", results);
        Assert.assertEquals(0, results.getNbrBatches());
        
    }

}
