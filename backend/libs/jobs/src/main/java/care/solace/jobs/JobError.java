package care.solace.jobs;

import care.solace.errors.ErrorCode;

/** Errors of the jobs admin API; titles in each service's messages properties. */
enum JobError implements ErrorCode {
  /** Safety, money or a fixed decision depends on this job: it cannot be paused or rescheduled. */
  LOCKED("job.locked", 409),
  /** The row's version is not the one read. */
  CHANGED("job.changed", 409),
  /** Not a valid cron with its zone, or an interval under 5 seconds. */
  SCHEDULE_INVALID("job.schedule-invalid", 400);

  private final String code;
  private final int status;

  JobError(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String area() {
    return "JOB";
  }
}
