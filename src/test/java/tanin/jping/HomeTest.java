package tanin.jping;

import org.junit.jupiter.api.Test;

public class HomeTest extends Base {
  @Test
  void visitHome() throws InterruptedException {
    go("/");
    assertContains(elem("body").getText(), "JPing");
  }
}
