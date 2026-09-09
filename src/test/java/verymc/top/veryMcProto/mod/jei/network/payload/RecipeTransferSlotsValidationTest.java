package verymc.top.veryMcProto.mod.jei.network.payload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 转移包槽 id 纯校验单测。语义 = 上游 {@code PacketRecipeTransferWithResult.getSlots}：
 * 列表长度不得超过容器槽数；每个 id ∈ [0, containerSize)。
 */
class RecipeTransferSlotsValidationTest
{
    @Test
    void validIds()
    {
        assertTrue(AbstractRecipeTransferPacket.validateSlotIds(List.of(0, 1, 2), 9));
        assertTrue(AbstractRecipeTransferPacket.validateSlotIds(List.of(), 9));
        assertTrue(AbstractRecipeTransferPacket.validateSlotIds(List.of(8), 9));
    }

    @Test
    void negativeIdRejected()
    {
        assertFalse(AbstractRecipeTransferPacket.validateSlotIds(List.of(0, -1), 9));
    }

    @Test
    void outOfRangeRejected()
    {
        assertFalse(AbstractRecipeTransferPacket.validateSlotIds(List.of(9), 9));
        assertFalse(AbstractRecipeTransferPacket.validateSlotIds(List.of(0, 1, 100), 9));
    }

    @Test
    void oversizedListRejected()
    {
        // 上游防护：slotIds.size() > container.slots.size() 直接拒绝
        assertFalse(AbstractRecipeTransferPacket.validateSlotIds(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 9));
        assertTrue(AbstractRecipeTransferPacket.validateSlotIds(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0), 9));
    }
}
