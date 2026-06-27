package nl.jessedezwart.piglinpanel.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(Exception.class)
  public String handleException(Exception ex, Model model) {
    log.error("Unhandled request exception", ex);
    model.addAttribute("errorMessage", ex.getMessage());
    return "fragments/error :: error-banner";
  }
}
