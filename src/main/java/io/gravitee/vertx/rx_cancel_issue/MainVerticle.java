package io.gravitee.vertx.rx_cancel_issue;


import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.plugins.RxJavaPlugins;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.RxHelper;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class MainVerticle extends AbstractVerticle {

  private HttpServer rxHttpServer;
  private Disposable requestDisposable;

  public static void main(String[] args) {
    final Vertx vertx = Vertx.vertx();

    vertx.deployVerticle(new MainVerticle());
  }

  @Override
  public Completable rxStart() {
    // Set global error handler to catch everything that has not been properly caught.
    RxJavaPlugins.setErrorHandler(throwable -> log.warn("An unexpected error occurred", throwable));

    // Reconfigure RxJava to use Vertx schedulers.
    RxJavaPlugins.setComputationSchedulerHandler(s -> RxHelper.scheduler(vertx));
    RxJavaPlugins.setIoSchedulerHandler(s -> RxHelper.blockingScheduler(vertx));
    RxJavaPlugins.setNewThreadSchedulerHandler(s -> RxHelper.scheduler(vertx));

    this.rxHttpServer = vertx.createHttpServer();

    // Listen and dispatch http requests.
    this.requestDisposable = rxHttpServer.requestStream().toFlowable().flatMapCompletable(this::dispatchRequest).subscribe();

    return rxHttpServer
      .rxListen()
      .ignoreElement()
      .doOnComplete(() -> log.info("HTTP listener ready to accept requests on port {}", rxHttpServer.actualPort()))
      .doOnError(throwable -> log.error("Unable to start HTTP Server", throwable.getCause()));
  }

  private Completable dispatchRequest(HttpServerRequest request) {
    return request.response().rxSend(generateMessageFlow())
      .doOnComplete(() -> log.debug("Request properly dispatched"))
      .onErrorResumeNext(t -> handleError(t, request.response()))
      .doOnSubscribe(dispatchDisposable -> configureCloseHandler(request, dispatchDisposable));
  }

  private Flowable<Buffer> generateMessageFlow() {
    return Flowable
      .<Buffer, Long>generate(
        () -> 0L,
        (state, emitter) -> {
          emitter.onNext(Buffer.buffer("Chunk " + state + "\n"));
          return state + 1;
        }
      )
      .delay(1000, TimeUnit.MILLISECONDS)
      .rebatchRequests(1)
      .doOnCancel(() -> log.info("Cancelled. Stop generating chunks")) // <-- Never executed.
      .doOnSubscribe(subscription -> log.info("Start generating chunks"));
  }

  private Completable handleError(Throwable throwable, HttpServerResponse response) {
    log.error("An unexpected error occurred while dispatching request", throwable);

    return tryEndResponse(response);
  }

  private Completable tryEndResponse(HttpServerResponse response) {
    try {
      if (!response.ended()) {
        if (!response.headWritten()) {
          response.setStatusCode(500);
        }

        // Try to end the response and complete normally in case of error.
        return response
          .rxEnd()
          .doOnError(throwable -> log.error("Failed to properly end response after error", throwable))
          .onErrorComplete();
      }

      return Completable.complete();
    } catch (Throwable throwable) {
      log.error("Failed to properly end response after error", throwable);
      return Completable.complete();
    }
  }

  private void configureCloseHandler(HttpServerRequest request, Disposable dispatchDisposable) {
    request
      .connection()
      // Must be added to ensure closed connection disposes underlying subscription
      .closeHandler(event -> dispatchDisposable.dispose());
  }

  @Override
  public Completable rxStop() {
    log.info("Stopping HTTP Server...");
    return Completable
      .fromRunnable(() -> requestDisposable.dispose())
      .andThen(rxHttpServer.rxClose().doOnComplete(() -> log.info("HTTP Server has been correctly stopped")));
  }
}
