package com.example;

import app.spellscroll.api.annotations.SpellscrollPlugin;
import app.spellscroll.api.plugin.ISpellscrollPlugin;
import app.spellscroll.api.plugin.ISpellscrollPluginContext;

@SpellscrollPlugin(
    value = TestPluginConstants.PLUGIN_ID,
    name = TestPluginConstants.PLUGIN_NAME,
    version = TestPluginConstants.PLUGIN_VERSION
)
public class ExamplePlugin implements ISpellscrollPlugin
{
    @Override
    public void initialize(ISpellscrollPluginContext context)
    {

    }
}
