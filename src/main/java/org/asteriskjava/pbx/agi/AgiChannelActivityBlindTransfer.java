package org.asteriskjava.pbx.agi;

import java.util.concurrent.CountDownLatch;

import org.asteriskjava.fastagi.AgiChannel;
import org.asteriskjava.fastagi.AgiException;
import org.asteriskjava.pbx.AgiChannelActivityAction;
import org.asteriskjava.pbx.Channel;

public class AgiChannelActivityBlindTransfer implements AgiChannelActivityAction
{

    CountDownLatch latch = new CountDownLatch(1);
    private String target;
    private String sipHeader;
    int timeout = 30;

    public AgiChannelActivityBlindTransfer(String fullyQualifiedName, String sipHeader)
    {
        this.target = fullyQualifiedName;
        this.sipHeader = sipHeader;
        if (sipHeader == null)
        {
            this.sipHeader = "";
        }
    }

    @Override
    public void execute(AgiChannel channel, Channel ichannel) throws AgiException, InterruptedException
    {

        channel.setVariable("__SIPADDHEADER", sipHeader);
        ichannel.setCurrentActivityAction(new AgiChannelActivityHold());
        channel.dial(target, timeout, "");

    }

    @Override
    public boolean isDisconnect()
    {
        return false;
    }

    @Override
    public void cancel(Channel channel)
    {
        latch.countDown();

    }
}
