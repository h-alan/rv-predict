/* This file was generated by SableCC (http://www.sablecc.org/). */

package soot.jimple.parser.node;

import soot.jimple.parser.analysis.*;

@SuppressWarnings("nls")
public final class TCmp extends Token
{
    public TCmp()
    {
        super.setText("cmp");
    }

    public TCmp(int line, int pos)
    {
        super.setText("cmp");
        setLine(line);
        setPos(pos);
    }

    @Override
    public Object clone()
    {
      return new TCmp(getLine(), getPos());
    }

    public void apply(Switch sw)
    {
        ((Analysis) sw).caseTCmp(this);
    }

    @Override
    public void setText(@SuppressWarnings("unused") String text)
    {
        throw new RuntimeException("Cannot change TCmp text.");
    }
}
