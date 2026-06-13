package nl.jessedezwart.piglinpanel.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(Exception.class)
  public String handleException(Exception ex, Model model, HttpServletRequest request) {
    log.error("Request failed: {}", request.getRequestURI(), ex);
    model.addAttribute("errorMessage", ex.getMessage());
    if (request.getHeader("HX-Request") != null) {
      return "fragments/error :: error-banner";
    }
    return "fragments/error :: error-banner";
  }
}
