package me.jellysquid.mods.sodium.client.render.chunk.lists;

import java.util.Iterator;
import java.util.List;

/**
 * A one-frame list of the sections that intersect the MakeUp Low shadow volume. It is separate
 * from the camera-visible lists so rendering the shadow map cannot disturb the normal passes.
 */
public class ShadowRenderLists implements ChunkRenderListIterable {
    private final List<ChunkRenderList> lists;

    public ShadowRenderLists(List<ChunkRenderList> lists) {
        this.lists = lists;
    }

    @Override
    public Iterator<ChunkRenderList> iterator(boolean reverse) {
        return this.lists.iterator();
    }
}
