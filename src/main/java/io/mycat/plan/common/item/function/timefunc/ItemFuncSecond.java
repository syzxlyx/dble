package io.mycat.plan.common.item.function.timefunc;

import java.math.BigInteger;
import java.util.List;

import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.primary.ItemIntFunc;
import io.mycat.plan.common.time.MySQLTime;

public class ItemFuncSecond extends ItemIntFunc {

	public ItemFuncSecond(List<Item> args) {
		super(args);
	}
	
	@Override
	public final String funcName(){
		return "second";
	}

	@Override
	public BigInteger valInt() {
		MySQLTime ltime = new MySQLTime();
		return getArg0Time(ltime) ? BigInteger.ZERO : BigInteger.valueOf(ltime.second);
	}

	@Override
	public void fixLengthAndDec() {
		fixCharLength(2); /* 0..59 */
		maybeNull = true;
	}

}