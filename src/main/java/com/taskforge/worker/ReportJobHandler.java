package com.taskforge.worker;

import com.taskforge.domain.JobPayload;
import com.taskforge.domain.JobType;
import com.taskforge.domain.ReportPayload;
import org.springframework.stereotype.Component;

@Component
class ReportJobHandler implements JobHandler {

    @Override
    public JobType jobType() {
        return JobType.GENERATE_REPORT;
    }

    @Override
    public String execute(JobPayload payload) {
        if (!(payload instanceof ReportPayload report)) {
            throw new IllegalArgumentException("Report handler requires a report payload");
        }
        return "# " + report.reportName() + "\n\nSimulated report preview.";
    }
}
