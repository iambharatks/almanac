class Solution {
public:
    int findGCD(vector<int>& nums) {
        int minE = 1001,maxE = 0;
        for(int& i : nums){
            minE=min(i,minE);
            maxE=max(i,maxE);
        }
        return __gcd(minE,maxE);
    }
};