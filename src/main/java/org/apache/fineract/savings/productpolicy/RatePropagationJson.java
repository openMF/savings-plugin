/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Stable explicit time representation for durable plan hashes, without reflective JDK access. */
final class RatePropagationJson {
  private RatePropagationJson() {}

  // Gson's JsonObject adapter rejects an explicit JsonNull on record fields. Legacy accounts
  // legitimately have no previous approved core snapshot, and must remain reportable/blocked.
  private static final TypeAdapterFactory ACCOUNT_SNAPSHOT =
      new TypeAdapterFactory() {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
          if (type.getRawType() != RatePropagationData.AccountSnapshot.class) return null;
          TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
          return new TypeAdapter<T>() {
            @Override
            public T read(JsonReader in) throws IOException {
              JsonObject object = JsonParser.parseReader(in).getAsJsonObject();
              if (object.has("previousCore") && object.get("previousCore").isJsonNull())
                object.remove("previousCore");
              return delegate.fromJsonTree(object);
            }

            @Override
            public void write(JsonWriter out, T value) throws IOException {
              delegate.write(out, value);
            }
          }.nullSafe();
        }
      };

  static final Gson JSON =
      ProductPolicyJson.create(true)
          .newBuilder()
          .registerTypeAdapterFactory(ACCOUNT_SNAPSHOT)
          .registerTypeAdapter(
              LocalDate.class,
              (JsonSerializer<LocalDate>) (v, t, c) -> new JsonPrimitive(v.toString()))
          .registerTypeAdapter(
              LocalDate.class,
              (JsonDeserializer<LocalDate>) (v, t, c) -> LocalDate.parse(v.getAsString()))
          .registerTypeAdapter(
              LocalDateTime.class,
              (JsonSerializer<LocalDateTime>) (v, t, c) -> new JsonPrimitive(v.toString()))
          .registerTypeAdapter(
              LocalDateTime.class,
              (JsonDeserializer<LocalDateTime>) (v, t, c) -> LocalDateTime.parse(v.getAsString()))
          .registerTypeAdapter(
              Instant.class, (JsonSerializer<Instant>) (v, t, c) -> new JsonPrimitive(v.toString()))
          .registerTypeAdapter(
              Instant.class,
              (JsonDeserializer<Instant>) (v, t, c) -> Instant.parse(v.getAsString()))
          .create();
}
