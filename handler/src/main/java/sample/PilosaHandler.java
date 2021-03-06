/*
Copyright (c) 2019 Pilosa

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software. (As clarification, there is no
requirement that the copyright notice and permission be included in binary
distributions of the Software.)

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package sample;

import com.pilosa.client.PilosaClient;
import com.pilosa.client.orm.*;
import oracle.goldengate.datasource.*;
import oracle.goldengate.datasource.meta.DsMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PilosaHandler extends AbstractHandler {

    // === Required methods ===

    @Override
    public void init(DsConfiguration conf, DsMetaData metaData) {
        logger.info("PilosaHandler initialized.");
        this.pilosaClient = PilosaClient.withAddress(this.address, this.address);
        createSchema();
        super.init(conf, metaData);
    }

    @Override
    public GGDataSource.Status operationAdded(DsEvent e, DsTransaction tx, DsOperation op) {
        super.operationAdded(e, tx, op);
        logger.debug("event added: {}", e.toString());
        final List<DsOperation> operations = tx.getOperations();
        for (DsOperation oper : operations) {
            DsOperation.OpType opType = oper.getOperationType();
            if (opType.isInsert()) {
                insertOp(oper);
            } else if (opType.isUpdate()) {
                updateOp(oper);
            } else if (opType.isDelete()) {
                deleteOp(oper);
            }
        }

        if (employeesBatch.size() > 0) {
            pilosaClient.query(employeesBatch);
        }

        resetBatch();
        return GGDataSource.Status.OK;
    }

    @Override
    public GGDataSource.Status transactionCommit(DsEvent e, DsTransaction tx) {
        return super.transactionCommit(e, tx);
    }

    @Override
    public String reportStatus() {
        return "";
    }

    @Override
    public GGDataSource.Status metaDataChanged(DsEvent e, DsMetaData meta) {
        return super.metaDataChanged(e, meta);
    }

    @Override
    public void destroy() {
        super.destroy();
    }

    // ===================


    public PilosaHandler setAddress(String address) {
        this.address = address;
        return this;
    }

    private void createSchema() {
        schema = this.pilosaClient.readSchema();
        // Create the indexes and fields
        employeesIndex = schema.index("employees");
        employeesJob = employeesIndex.field("job", FieldOptions.builder()
            .fieldSet()
            .setKeys(true)
            .build());
        employeesSalary = employeesIndex.field("salary", FieldOptions.builder()
            .fieldInt(0, 1000000)
            .build());
        employeesOk = employeesIndex.field("ok", FieldOptions.builder()
            .fieldBool()
            .build());
        pilosaClient.syncSchema(this.schema);

        employeesBatch = employeesIndex.batchQuery();
    }

    private void resetBatch() {
        employeesBatch.clear();
    }


    private void insertOp(DsOperation op) {
        logger.info("INSERT " + op.getTableName() + " " + op.getRecord().toString());
        if (op.getTableName().getShortName().equals("EMPLOYEES")) {
            DsRecord record = op.getRecord();
            long employeeID = Long.valueOf(record.getAfterValue(0));

            String job = record.getAfterValue(6);
            employeesBatch.add(employeesJob.set(job, employeeID));

            long salary = Float.valueOf(record.getAfterValue(7)).longValue();
            employeesBatch.add(employeesSalary.set(salary, employeeID));

            Map<String, Object> columnAttrs = new HashMap<>();
            String email = record.getAfterValue(3);
            columnAttrs.put("email", email);
            employeesBatch.add(employeesIndex.setColumnAttrs(employeeID, columnAttrs));

            // Mark this employee exists.
            // This is not required, but enables deleting an employee.
            employeesBatch.add(employeesOk.set(true, employeeID));
        }
    }

    private void updateOp(DsOperation op) {
        logger.info("UPDATE " + op.getTableName() + " " + op.getRecord().toString());
        if (op.getTableName().getShortName().equals("EMPLOYEES")) {
            DsRecord record = op.getRecord();
            long employeeID = Long.valueOf(record.getAfterValue(0));

            String job = record.getAfterValue(6);
            if (!job.equals("")) {
                employeesBatch.add(employeesJob.set(job, employeeID));
            }

            String salaryStr = record.getAfterValue(7);
            if (!salaryStr.equals("")) {
                employeesBatch.add(employeesSalary.set(Float.valueOf(salaryStr).longValue(), employeeID));
            }

            String email = record.getAfterValue(3);
            if (!email.equals("")) {
                Map<String, Object> columnAttrs = new HashMap<>();
                columnAttrs.put("email", email);
                employeesBatch.add(employeesIndex.setColumnAttrs(employeeID, columnAttrs));
            }
        }
    }

    private void deleteOp(DsOperation op) {
        logger.info("DELETE " + op.getTableName() + " " + op.getRecord().toString());
        if (op.getTableName().getShortName().equals("EMPLOYEES")) {
            DsRecord record = op.getRecord();
            long employeeID = Long.valueOf(record.getBeforeValue(0));
            // Mark the employee as don't exist.
            employeesBatch.add(employeesIndex.field("ok").set(false, employeeID));
        }
    }

    private final static Logger logger = LoggerFactory.getLogger(PilosaHandler.class);
    private PilosaClient pilosaClient;
    private Schema schema;
    private Index employeesIndex;
    private Field employeesJob;
    private Field employeesSalary;
    private Field employeesOk;
    private PqlBatchQuery employeesBatch;
    private String address;
}
