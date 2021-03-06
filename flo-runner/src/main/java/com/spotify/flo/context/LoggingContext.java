/*-
 * -\-\-
 * flo runner
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.flo.context;

import com.spotify.flo.EvalContext;
import com.spotify.flo.Fn;
import com.spotify.flo.Task;
import com.spotify.flo.TaskId;
import java.time.Duration;
import java.util.Objects;

/**
 * A {@link EvalContext} that integrates evaluation with the {@link Logging} interface
 */
class LoggingContext extends ForwardingEvalContext {

  private final Logging logging;

  private LoggingContext(EvalContext baseContext, Logging logging) {
    super(baseContext);
    this.logging = Objects.requireNonNull(logging);
  }

  static EvalContext composeWith(EvalContext baseContext, Logging logging) {
    return new LoggingContext(baseContext, logging);
  }

  @Override
  public <T> Value<T> evaluateInternal(Task<T> task, EvalContext context) {
    logging.willEval(task.id());
    return super.evaluateInternal(task, context);
  }

  @Override
  public <T> Value<T> invokeProcessFn(TaskId taskId, Fn<T> processFn) {
    logging.startEval(taskId);
    final Value<T> value = super.invokeProcessFn(taskId, processFn);
    final long t0 = System.nanoTime();
    value.consume(v -> logging.completedValue(taskId, v, Duration.ofNanos(System.nanoTime() - t0)));
    value.onFail(e -> logging.failedValue(taskId, e, Duration.ofNanos(System.nanoTime() - t0)));
    return value;
  }
}
