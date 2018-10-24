/*-
 * -\-\-
 * Flo Workflow Definition
 * --
 * Copyright (C) 2016 - 2017 Spotify AB
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

package com.spotify.flo;

import static com.spotify.flo.Values.toValueList;
import static java.util.stream.Collectors.toList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal utility functions for the {@link TaskBuilder} api implementation
 */
class BuilderUtils {

  static {
    // Best effort. Hope that ObjectOutputStream has not been loaded yet :pray:
    System.setProperty("sun.io.serialization.extendedDebugInfo", "true");
  }

  private static final Logger log = LoggerFactory.getLogger(BuilderUtils.class);

  private BuilderUtils() {
  }

  /**
   * Converts an array of {@link Fn}s of {@link Task}s to a {@link Fn} of a list of
   * those tasks {@link Task}s.
   *
   * It will only evaluate the functions (through calling {@link Fn#get()})
   * when the returned function is invoked. Thus it retains laziness.
   *
   * @param tasks  An array of lazy evaluated tasks
   * @return A function of a list of lazily evaluated tasks
   */
  @SafeVarargs
  static Fn<List<Task<?>>> lazyList(Fn<? extends Task<?>>... tasks) {
    return () -> Stream.of(tasks)
        .map(Fn::get)
        .collect(toList());
  }

  @SafeVarargs
  static <T> Fn<List<T>> lazyFlatten(Fn<? extends List<? extends T>>... lists) {
    return () -> Stream.of(lists)
        .map(Fn::get)
        .flatMap(List::stream)
        .collect(toList());
  }

  static <T> List<T> appendToList(List<T> list, T t) {
    final List<T> newList = new ArrayList<>(list);
    newList.add(t);
    return newList;
  }

  static <T, S> ProcessFnArg contextArg(TaskContext<T, S> taskContext) {
    return ec -> ec.value(() -> taskContext.provide(ec));
  }

  static <T> ProcessFnArg inputArg(Fn<Task<T>> task) {
    return ec -> ec.evaluate(task.get());
  }

  static <T> ProcessFnArg inputsArg(Fn<List<Task<T>>> task) {
    return ec -> task.get().stream()
        .map(ec::evaluate)
        .collect(toValueList(ec));
  }

  static void guardedCall(Runnable call) {
    try {
      call.run();
    } catch (Throwable t) {
      log.warn("Exception", t);
    }
  }

  static <T extends Serializable> T requireSerializable(T o, String name) {
    try {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      final ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(o);
      oos.flush();
      final ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
      final ObjectInputStream ois = new ContextualObjectInputStream(bais);
      @SuppressWarnings("unchecked") final T deserialized = (T) ois.readObject();
      return deserialized;
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalArgumentException(name + " not serializable: " + o, e);
    }
  }

  // https://github.com/apache/beam/blob/master/sdks/java/core/src/main/java/org/apache/beam/sdk/util/SerializableUtils.java#L162
  private static final class ContextualObjectInputStream extends ObjectInputStream {
    private ContextualObjectInputStream(final InputStream in) throws IOException {
      super(in);
    }

    @Override
    protected Class<?> resolveClass(final ObjectStreamClass classDesc)
        throws IOException, ClassNotFoundException {
      // note: staying aligned on JVM default but can need class filtering here to avoid 0day issue
      final String n = classDesc.getName();
      final ClassLoader classloader = findClassLoader();
      try {
        return Class.forName(n, false, classloader);
      } catch (final ClassNotFoundException e) {
        return super.resolveClass(classDesc);
      }
    }

    @Override
    protected Class resolveProxyClass(final String[] interfaces)
        throws IOException, ClassNotFoundException {
      final ClassLoader classloader = findClassLoader();

      final Class[] cinterfaces = new Class[interfaces.length];
      for (int i = 0; i < interfaces.length; i++) {
        cinterfaces[i] = classloader.loadClass(interfaces[i]);
      }

      try {
        return Proxy.getProxyClass(classloader, cinterfaces);
      } catch (final IllegalArgumentException e) {
        throw new ClassNotFoundException(null, e);
      }
    }

    public static ClassLoader findClassLoader() {
      return findClassLoader(Thread.currentThread().getContextClassLoader());
    }

    public static ClassLoader findClassLoader(final ClassLoader proposed) {
      ClassLoader classLoader = proposed;
      if (classLoader == null) {
        classLoader = BuilderUtils.class.getClassLoader();
      }
      if (classLoader == null) {
        classLoader = ClassLoader.getSystemClassLoader();
      }
      return classLoader;
    }
  }
}
