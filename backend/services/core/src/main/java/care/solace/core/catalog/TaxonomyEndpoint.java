package care.solace.core.catalog;

import care.solace.contracts.core.v1.ListSpecialtiesRequest;
import care.solace.contracts.core.v1.ListSpecialtiesResponse;
import care.solace.contracts.core.v1.Specialty;
import care.solace.contracts.core.v1.TaxonomyServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The specialty taxonomy for Tracking's invite rules; Tracking caches it for an hour. */
@Service
class TaxonomyEndpoint extends TaxonomyServiceGrpc.TaxonomyServiceImplBase {

  private final JdbcClient db;

  TaxonomyEndpoint(JdbcClient db) {
    this.db = db;
  }

  @Override
  public void listSpecialties(
      ListSpecialtiesRequest request, StreamObserver<ListSpecialtiesResponse> out) {
    var response = ListSpecialtiesResponse.newBuilder();
    db.sql("SELECT code, enabled FROM specialty ORDER BY position")
        .query(
            rs -> {
              response.addSpecialties(
                  Specialty.newBuilder()
                      .setKey(rs.getString("code"))
                      .setActive(rs.getBoolean("enabled")));
            });
    out.onNext(response.build());
    out.onCompleted();
  }
}
