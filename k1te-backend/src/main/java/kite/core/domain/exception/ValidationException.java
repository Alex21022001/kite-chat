/* LGPL 3.0 ©️ Dmytro Zemnytskyi, pragmasoft@gmail.com, 2023-2024 */
package kite.core.domain.exception;

public class ValidationException extends KiteException {

  private static final long serialVersionUID = 1L;
  private static final int VALIDATION_ERROR = 400;

  /**
   * @param message
   */
  public ValidationException(String message) {
    super(message);
  }

  /**
   * @param message
   * @param cause
   */
  public ValidationException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public int code() {
    return VALIDATION_ERROR;
  }
}
