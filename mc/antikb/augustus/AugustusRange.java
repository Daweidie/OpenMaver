/** Integer branch of 47Ts: cached sample, optional Gaussian, repeat rejection,
 * and the original outlier counter and endpoint arithmetic. */
final class AugustusRange {
    final String mode,key;
    int current,samples,outlierDelay;
    AugustusRange(String mode,String key) {this.mode=mode;this.key=key;}
    boolean advanced() {return mode.equals("Legit")&&key.equals("JumpInterval");}
    double v(String suffix) {return AugustusVelocity.value(mode,key+suffix);}
    boolean b(String suffix) {return advanced()&&v(suffix)>.5;}
    static int uniform(int min,int max) {return min==max?min:Math.min(min,max)+AugustusVelocity.RANDOM.nextInt(Math.abs(max-min)+1);}
    void resetOutliers() {samples=0;if(advanced())outlierDelay=uniform((int)v("OutlierDelayMin"),(int)v("OutlierDelayMax"));}
    int nominal(int lo,int hi) {
        if(!b("NormalDistribution"))return uniform(lo,hi);
        double center=lo+(hi-lo)*v("Center")/100;
        for(int i=0;i<50;i++) {
            double n=AugustusVelocity.RANDOM.nextGaussian()*v("Sigma")+center;
            // Source checks the truncated upper bound before rounding/clamping.
            if((int)n<=hi&&n>=lo)return (int)Math.max(lo,Math.min(hi,Math.round(n)));
        }
        return (int)Math.max(lo,Math.min(hi,Math.round(lo+(hi-lo)*AugustusVelocity.RANDOM.nextDouble())));
    }
    int sample() {
        int lo=Math.min((int)v("Min"),(int)v("Max")),hi=Math.max((int)v("Min"),(int)v("Max"));
        int next=nominal(lo,hi);
        if(b("NotInARow")&&lo!=hi)for(int tries=0;tries<30&&next==current;tries++)next=nominal(lo,hi);
        boolean outlier=b("Outliers")&&samples>outlierDelay;
        if(outlier)resetOutliers();samples++;
        if(outlier) {
            int a=Math.min((int)v("OutlierValueMin"),(int)v("OutlierValueMax")),z=Math.max((int)v("OutlierValueMin"),(int)v("OutlierValueMax"));
            int below=Math.max(0,lo-a),above=Math.max(0,z-hi),total=below+above;
            if(total>0) {
                int index=below==0?uniform(0,Math.max(0,z-Math.max(a,hi)))+1:
                        above==0?uniform(0,Math.max(0,Math.min(z,lo)-a)):uniform(0,total-1);
                next=index<below?a+index:z-(index-1);
            }
        }
        current=next;return next;
    }
}
