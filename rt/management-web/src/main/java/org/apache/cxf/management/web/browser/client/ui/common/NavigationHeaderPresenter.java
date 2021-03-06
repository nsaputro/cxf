/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.management.web.browser.client.ui.common;

import javax.annotation.Nonnull;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import org.apache.cxf.management.web.browser.client.EventBus;
import org.apache.cxf.management.web.browser.client.event.GoToSettingsEvent;
import org.apache.cxf.management.web.browser.client.ui.BasePresenter;
import org.apache.cxf.management.web.browser.client.ui.BindStrategy;

public class NavigationHeaderPresenter extends BasePresenter implements NavigationHeaderView.Presenter {

    @Nonnull
    private NavigationHeaderView view;

    @Inject
    public NavigationHeaderPresenter(@Nonnull final EventBus eventBus,
            @Nonnull final NavigationHeaderView view,
            @Nonnull @Named("BindStrategyForNavigationHeader") final BindStrategy bindStrategy) {
        super(eventBus, view, bindStrategy);

        this.view = view;
        this.view.setPresenter(this);
    }

    public void onSettingsButtonClicked() {
        eventBus.fireEvent(new GoToSettingsEvent());
    }
}
