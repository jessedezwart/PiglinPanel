package nl.jessedezwart.piglinpanel.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight liveness endpoint used by the UI to detect when the backend becomes unreachable. */
@RestController
public class HealthController {

  @GetMapping("/ping")
  public ResponseEntity<String> ping() {
    return ResponseEntity.ok("ok");
  }
}
