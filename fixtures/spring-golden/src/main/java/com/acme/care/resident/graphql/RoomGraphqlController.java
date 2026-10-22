package com.acme.care.resident.graphql;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.acme.care.resident.graphql.ResidentGraphqlController.ResidentView;
import com.acme.care.resident.service.RoomGraphService;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
@SchemaMapping(typeName = "Room")
public class RoomGraphqlController {

    public record RoomView(UUID id, String number) {
    }

    public record BedView(UUID id, String label, boolean occupied) {
    }

    private final RoomGraphService rooms;

    public RoomGraphqlController(RoomGraphService rooms) {
        this.rooms = rooms;
    }

    @SchemaMapping
    public List<BedView> beds(RoomView room) {
        return rooms.bedsOf(room.id());
    }

    @BatchMapping
    public Map<RoomView, List<ResidentView>> occupants(List<RoomView> roomsToLoad) {
        return rooms.occupantsOf(roomsToLoad);
    }
}
