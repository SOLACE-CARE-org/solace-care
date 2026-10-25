package care.solace.lists;

import java.util.List;

/** Several {@link ReviewedList}s defined together, such as one evaluation set per AI feature. */
public interface ReviewedListGroup {

  List<ReviewedList> lists();
}
