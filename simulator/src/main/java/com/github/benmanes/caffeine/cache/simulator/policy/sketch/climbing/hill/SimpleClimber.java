/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.typesafe.config.Config;

/**
 * A naive, simple hill climber.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SimpleClimber extends AbstractClimber {
  private final double sampleDecayRate;
  private final double stepDecayRate;
  private final double tolerance;

  private boolean increaseWindow;
  private double stepSize;

  public SimpleClimber(Config config) {
    SimpleClimberSettings settings = new SimpleClimberSettings(config);
    this.sampleSize = (int) (settings.percentSample() * settings.maximumSize());
    this.stepSize = (int) (settings.percentPivot() * settings.maximumSize());
    this.sampleDecayRate = settings.sampleDecayRate();
    this.stepDecayRate = settings.stepDecayRate();
    this.tolerance = 100d * settings.tolerance();
  }

  @Override
  protected double adjust(double hitRate) {
    if (hitRate < (previousHitRate + tolerance)) {
      increaseWindow = !increaseWindow;
    }
    return increaseWindow ? stepSize : -stepSize;
  }

  @Override
  protected void resetSample(double hitRate) {
    super.resetSample(hitRate);

    stepSize *= stepDecayRate;
    sampleSize = (int) (sampleSize * sampleDecayRate);
    if ((stepSize <= 0.01) || (sampleSize <= 1)) {
      sampleSize = Integer.MAX_VALUE;
    }
  }

  static final class SimpleClimberSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.simple.";

    public SimpleClimberSettings(Config config) {
      super(config);
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public double tolerance() {
      return config().getDouble(BASE_PATH + "tolerance");
    }
    public double stepDecayRate() {
      return config().getDouble(BASE_PATH + "step-decay-rate");
    }
    public double sampleDecayRate() {
      return config().getDouble(BASE_PATH + "sample-decay-rate");
    }
  }
}
