package edu.harvard.dbmi.avillach.dictionary.dashboarddrawer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/dashboard-drawer")
public class DashboardDrawerController {

    @Autowired
    private DashboardDrawerService dashboardDrawerService;

    @GetMapping
    public ResponseEntity<DashboardDrawerList> findAll() {
        return ResponseEntity.ok(dashboardDrawerService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DashboardDrawer> findByDatasetId(@PathVariable Integer id) {
        return ResponseEntity.ok(dashboardDrawerService.findByDatasetId(id));
    }
}
