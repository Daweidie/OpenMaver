import module.ModuleManager;
import module.movement.Scaffold;

/** Continuous bridges using actual hooks, collision faces and vanilla ground acceleration. */
public final class ScaffoldNormalBridgeTest {
    public static void main(String[] args) throws Exception {
        ModuleManager.register(new Scaffold());
        if (args.length == 3) {
            bridge(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[0]));
            return;
        }
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int origin : new int[] {0,1_000_000}) {
            for (int[] direction : directions) bridge(direction[0],direction[1],origin);
        }
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        System.out.println("[scaffold-normal-bridge-test] " + ScaffoldNormalTest.checks + " checks passed");
    }

    private static void bridge(int forward, int strafe, int origin) throws Exception {
        ScaffoldNormalTest.fresh(origin);
        // A short starting platform allows the configured rotation acceleration to settle.
        for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++)
            ScaffoldNormalTest.world.add(origin+x,63,origin+z);
        ScaffoldNormalTest.keys.keyBindForward.pressed = forward>0;
        ScaffoldNormalTest.keys.keyBindBack.pressed = forward<0;
        ScaffoldNormalTest.keys.keyBindLeft.pressed = strafe>0;
        ScaffoldNormalTest.keys.keyBindRight.pressed = strafe<0;
        ScaffoldNormalTest.Player p = ScaffoldNormalTest.player;
        float lastYaw = p.rotationYaw;
        for (int tick=0; tick<100; tick++) {
            ScaffoldNormalTest.start();
            float f=p.movementInput.moveForward*.98F, s=p.movementInput.moveStrafe*.98F;
            float length=(float)Math.sqrt(f*f+s*s);
            float factor=.1F/Math.max(1,length);
            double yaw=Math.toRadians(p.rotationYaw);
            p.motionX += s*factor*Math.cos(yaw)-f*factor*Math.sin(yaw);
            p.motionZ += f*factor*Math.cos(yaw)+s*factor*Math.sin(yaw);
            p.posX += p.motionX;
            p.posZ += p.motionZ;
            p.posY += p.motionY;
            boolean support=ScaffoldNormalTest.world.supports(p.posX,p.posZ);
            if (p.posY<=64 && support) { p.posY=64; p.motionY=0; p.onGround=true; }
            else { p.motionY=(p.motionY-.08)*.98; p.onGround=false; }
            String scenario="forward="+forward+" strafe="+strafe+" origin="+origin+" tick="+tick;
            if (Boolean.getBoolean("normal.test.trace"))
                System.out.printf("%s pos=%.3f/%.3f/%.3f aim=%.2f/%.2f placed=%d%n",scenario,
                    p.posX-origin,p.posY,p.posZ-origin,p.rotationYaw,p.rotationPitch,ScaffoldNormalTest.controller.placements);
            ScaffoldNormalTest.check(p.posY>=63.99,"continuous bridge retains floor support: "+scenario);
            ScaffoldNormalTest.check(!p.movementInput.jump && !p.movementInput.sneak,
                    "Normal has no old jump/sneak controller: "+scenario);
            ScaffoldNormalTest.check(Math.abs(p.rotationYaw-lastYaw)<=50.2,"Normal yaw obeys speed range");
            lastYaw=p.rotationYaw;
            ScaffoldNormalTest.end();
            p.motionX *= .546D;
            p.motionZ *= .546D;
        }
        ScaffoldNormalTest.check(ScaffoldNormalTest.controller.placements>=12,"bridge extends continuously in all directions");
    }
}
