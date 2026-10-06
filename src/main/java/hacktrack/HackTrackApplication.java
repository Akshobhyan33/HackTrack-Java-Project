package hacktrack;

import hacktrack.dao.Database;
import hacktrack.logic.ReminderScheduler;
import hacktrack.service.GmailService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HackTrackApplication {

    public static void main(String[] args) {
        Database.initialize();
        ReminderScheduler.start();
        GmailService.init();
        SpringApplication.run(HackTrackApplication.class, args);
    }
}
