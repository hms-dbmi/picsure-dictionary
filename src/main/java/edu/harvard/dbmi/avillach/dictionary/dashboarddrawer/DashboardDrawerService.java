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
     * @return a Dashboard instance with drawer-specific columns and rows.
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
     * @param datasetId the ID of the dataset to fetch.
     * @return a Dashboard instance with drawer-specific columns and rows.
     */
    public DashboardDrawer findByDatasetId(Integer datasetId) {
        if (dashboardLayout.equalsIgnoreCase("bdc")) {
            List<DashboardDrawer> records = repository.getDashboardDrawerRows(datasetId);
            // Should be atomic as the query is an aggregation on the dataset table.
            // Probably a better way to do this.
            if (records.size() == 1) {
                return records.getFirst();
            }
        }

        return new DashboardDrawer(-1, "", "", new ArrayList<>(), "", new ArrayList<>(), "", "");
    }
}
