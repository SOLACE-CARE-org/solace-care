package care.solace.keycloak.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContactTest {

  @Test
  void parsesAPhoneNumberTypedWithSpaces() {
    assertThat(Contact.parse(" +237 6 77 00 00 00 "))
        .isEqualTo(new Contact(Contact.Kind.PHONE, "+237677000000"));
  }

  @Test
  void parsesAnEmailInLowerCase() {
    assertThat(Contact.parse("Awa.Ngono@Example.CM"))
        .isEqualTo(new Contact(Contact.Kind.EMAIL, "awa.ngono@example.cm"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "677000000", "+0677000000", "not an email@", "a@b", "+12"})
  void rejectsAnythingElse(String input) {
    assertThat(Contact.parse(input)).isNull();
  }

  @Test
  void masksPhoneNumbers() {
    Contact phone = Contact.parse("+237677000012");

    assertThat(phone.masked()).isEqualTo("+237*******12");
    assertThat(phone.toString()).doesNotContain("677000");
  }

  @Test
  void masksEmails() {
    assertThat(Contact.parse("awa@example.cm").masked()).isEqualTo("a***@example.cm");
  }
}
