package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardDrawerService {

    private final DashboardDrawerRepository repository;
    private final String dashboardLayout;

    @Autowired
    public DashboardDrawerService(DashboardDrawerRepository repository, @Value("${dashboard.layout.type}") String dashboardLayout) {
        this.repository = repository;
        this.dashboardLayout = dashboardLayout;
    }

    /**
     * Retrieves the Dashboard Drawer for all datasets.
     *
     * @return All Dashboard Instances and their metadata.
     */
    public DashboardDrawerList findAll() {
        if (dashboardLayout.equalsIgnoreCase("bdc")) {
            List<DashboardDrawer> records = repository.getDashboardDrawerRows();
            return new DashboardDrawerList(records);
        }

        return new DashboardDrawerList(new ArrayList<>());
    }

    /**
     * Retrieves the Dashboard Drawer for a specific dataset.
     *
     *
     * @param datasetId the ID of the dataset to fetch.
     * @return a single Dashboard instance with drawer-specific metadata.
     */
    public DashboardDrawer findByDatasetId(Integer datasetId) {
        if (dashboardLayout.equalsIgnoreCase("bdc")) {
            List<DashboardDrawer> records = repository.getDashboardDrawerRows(datasetId);

            if (records.size() == 1) {
                return records.getFirst();
            }
        }

        return new DashboardDrawer(-1, "", "", new ArrayList<>(), "", new ArrayList<>(), "", "");
    }
}
